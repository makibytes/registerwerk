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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
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

        String contentHash = sha256Hex(content);

        KycDocument doc = new KycDocument();
        doc.setLegalEntityId(entityId);
        doc.setFileName(fileName);
        doc.setMimeType(mimeType);
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
            String s3Key = "kyc/" + entityId + "/" + UUID.randomUUID() + "/" + fileName;
            doc.setStorageRef(s3Key);
            saved = kycDocumentRepository.save(doc);

            s3DocumentStorageAdapter.upload(s3Key, content, mimeType);

            log.info("Stored document in S3: id={}, key={}", saved.getId(), s3Key);
        }

        eventPublisher.publishEvent(new DocumentUploadedEvent(saved.getId(), uploadedBy, actorRole,
                Map.of("entityId", entityId.toString(), "documentType", docType.name(), "fileName", fileName)));
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
    public byte[] retrieveContent(UUID docId) {
        KycDocument doc = kycDocumentRepository.findById(docId)
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
    public void softDeleteDocument(UUID docId, UUID actorId, String actorRole) {
        KycDocument doc = kycDocumentRepository.findById(docId)
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
}
