package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.events.DocumentDeletedEvent;
import de.makibytes.registerwerk.kyc.events.DocumentUploadedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentContent;
import de.makibytes.registerwerk.kyc.api.KycDocumentContentRepository;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.api.S3DocumentStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages KYC document storage with a tiered strategy:
 * small documents are stored inline in the database,
 * large documents are stored in S3.
 */
@Service
@Transactional
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String INLINE_STORAGE_REF = "inline";
    /** Documents ≤ 5 MB are stored inline. */
    private static final int MAX_INLINE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff",
            "application/xml", "text/xml", "application/octet-stream");

    private final KycDocumentRepository kycDocumentRepository;
    private final KycDocumentContentRepository kycDocumentContentRepository;
    private final S3DocumentStorageAdapter s3DocumentStorageAdapter;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
            KycDocumentRepository kycDocumentRepository,
            KycDocumentContentRepository kycDocumentContentRepository,
            S3DocumentStorageAdapter s3DocumentStorageAdapter,
            ApplicationEventPublisher eventPublisher) {
        this.kycDocumentRepository = kycDocumentRepository;
        this.kycDocumentContentRepository = kycDocumentContentRepository;
        this.s3DocumentStorageAdapter = s3DocumentStorageAdapter;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Stores a KYC document. Small files are kept inline; large files go to S3.
     *
     * @param entityId   owning legal entity
     * @param content    raw bytes of the file
     * @param fileName   original file name
     * @param mimeType   MIME type
     * @param docType    document classification
     * @param uploadedBy ID of the uploading user
     * @param actorRole  role of the uploading user (for audit)
     * @return saved {@link KycDocument} metadata record
     */
    public KycDocument storeDocument(
            UUID entityId,
            byte[] content,
            String fileName,
            String mimeType,
            KycDocument.DocumentType docType,
            UUID uploadedBy,
            String actorRole) {

        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Document content must not be empty");
        }
        String safeMimeType = MediaType.parseMediaType(mimeType).toString();
        if (!SUPPORTED_MIME_TYPES.contains(safeMimeType)) {
            throw new IllegalArgumentException("Unsupported KYC document MIME type: " + safeMimeType);
        }
        String safeFileName = safeFileName(fileName);
        String contentHash = sha256Hex(content);

        KycDocument doc = new KycDocument();
        doc.setLegalEntityId(entityId);
        doc.setFileName(safeFileName);
        doc.setMimeType(safeMimeType);
        doc.setDocumentType(docType);
        doc.setContentHash(contentHash);
        doc.setSizeBytes((long) content.length);
        doc.setUploadedBy(uploadedBy);

        KycDocument saved;
        if (content.length <= MAX_INLINE_BYTES) {
            doc.setStorageRef(INLINE_STORAGE_REF);
            saved = kycDocumentRepository.save(doc);

            KycDocumentContent inline = new KycDocumentContent();
            inline.setId(saved.getId());
            inline.setContent(content);
            kycDocumentContentRepository.save(inline);

            log.info("Stored document inline: id={}, entity={}", saved.getId(), entityId);
        } else {
            String s3Key = "kyc/" + entityId + "/" + UUID.randomUUID() + "/" + safeFileName;
            doc.setStorageRef(s3Key);
            saved = kycDocumentRepository.save(doc);

            registerS3RollbackCleanup(s3Key);
            s3DocumentStorageAdapter.upload(s3Key, content, safeMimeType);

            log.info("Stored document in S3: id={}, key={}", saved.getId(), s3Key);
        }

        eventPublisher.publishEvent(new DocumentUploadedEvent(saved.getId(), uploadedBy, actorRole,
                Map.of("entityId", entityId.toString(), "documentType", docType.name(), "fileName", safeFileName)));
        return saved;
    }

    /**
     * Retrieves the raw bytes for a document, transparently from inline storage or S3.
     *
     * @param docId document ID
     * @return raw content bytes
     * @throws EntityNotFoundException if document not found
     */
    @Transactional(readOnly = true)
    public byte[] retrieveContent(UUID entityId, UUID docId) {
        KycDocument doc = kycDocumentRepository.findByIdAndLegalEntityIdAndDeletedAtIsNull(docId, entityId)
            .orElseThrow(() -> new EntityNotFoundException("KycDocument", docId));

        if (INLINE_STORAGE_REF.equals(doc.getStorageRef())) {
            KycDocumentContent content = kycDocumentContentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("KycDocumentContent", docId));
            return content.getContent();
        } else {
            return s3DocumentStorageAdapter.download(doc.getStorageRef());
        }
    }

    /**
     * Soft-deletes a document by setting its deletedAt timestamp.
     *
     * @param docId     document to delete
     * @param actorId   ID of the actor performing deletion
     * @param actorRole role of the actor performing deletion (for audit)
     */
    public void softDeleteDocument(UUID entityId, UUID docId, UUID actorId, String actorRole) {
        KycDocument doc = kycDocumentRepository.findByIdAndLegalEntityIdAndDeletedAtIsNull(docId, entityId)
            .orElseThrow(() -> new EntityNotFoundException("KycDocument", docId));
        doc.setDeletedAt(Instant.now());
        kycDocumentRepository.save(doc);
        log.info("Soft-deleted document: id={}, by={}", docId, actorId);
        eventPublisher.publishEvent(new DocumentDeletedEvent(docId, actorId, actorRole,
                Map.of("entityId", doc.getLegalEntityId().toString())));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }
        String normalized = fileName.replace('\\', '/')
                .replace("\0", "")
                .replace("\r", "")
                .replace("\n", "");
        int lastSlash = normalized.lastIndexOf('/');
        String name = (lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized).trim();
        if (name.isBlank()) {
            return "document";
        }
        return name.length() <= 500 ? name : name.substring(0, 500);
    }

    private void registerS3RollbackCleanup(String s3Key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        s3DocumentStorageAdapter.delete(s3Key);
                    } catch (RuntimeException cleanupFailure) {
                        log.error("Could not remove orphaned S3 document after transaction rollback: key={}",
                                s3Key, cleanupFailure);
                    }
                }
            }
        });
    }
}
