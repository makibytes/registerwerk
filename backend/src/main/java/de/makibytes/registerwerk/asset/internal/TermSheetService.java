package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.TermSheetUploadedEvent;
import de.makibytes.registerwerk.asset.events.TermSheetDeletedEvent;
import de.makibytes.registerwerk.asset.events.TermSheetSyncedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentContent;
import de.makibytes.registerwerk.asset.api.AssetDocumentSource;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.asset.api.AssetDocumentContentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.kyc.api.S3DocumentStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages term sheet and other regulatory documents attached to an asset.
 * Supports direct upload and on-chain retrieval via ERC-1643 or token URI standards.
 * Documents are stored inline (≤ 5 MB) or in S3 (> 5 MB), matching DocumentService behaviour.
 */
@Service
@Transactional
public class TermSheetService {

    private static final Logger log = LoggerFactory.getLogger(TermSheetService.class);
    private static final String INLINE_STORAGE_REF = "inline";
    private static final int MAX_INLINE_BYTES = 5 * 1024 * 1024;

    /** MIME types accepted for term sheet documents. */
    public static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "application/pdf",
        "text/html",
        "text/plain",
        "text/markdown",
        "application/json",
        "application/xml",
        "text/xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final AssetDocumentRepository assetDocumentRepository;
    private final AssetDocumentContentRepository assetDocumentContentRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final S3DocumentStorageAdapter s3DocumentStorageAdapter;
    private final TermSheetOnChainFetchService onChainFetchService;
    private final ApplicationEventPublisher eventPublisher;
    private final de.makibytes.registerwerk.asset.api.AssetRepository assetRepository;

    public TermSheetService(
            AssetDocumentRepository assetDocumentRepository,
            AssetDocumentContentRepository assetDocumentContentRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            S3DocumentStorageAdapter s3DocumentStorageAdapter,
            TermSheetOnChainFetchService onChainFetchService,
            ApplicationEventPublisher eventPublisher,
            de.makibytes.registerwerk.asset.api.AssetRepository assetRepository) {
        this.assetDocumentRepository = assetDocumentRepository;
        this.assetDocumentContentRepository = assetDocumentContentRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.s3DocumentStorageAdapter = s3DocumentStorageAdapter;
        this.onChainFetchService = onChainFetchService;
        this.eventPublisher = eventPublisher;
        this.assetRepository = assetRepository;
    }

    /**
     * Uploads a document and attaches it to an asset.
     * Files ≤ 5 MB are stored inline; larger files go to S3.
     *
     * @param assetId    target asset UUID
     * @param content    raw file bytes
     * @param fileName   original filename
     * @param mimeType   detected or declared MIME type
     * @param type       document classification (e.g. TERM_SHEET)
     * @param uploadedBy UUID of the uploading user
     * @return saved {@link AssetDocument} metadata record
     */
    public AssetDocument uploadDocument(
            UUID assetId,
            byte[] content,
            String fileName,
            String mimeType,
            AssetDocumentType type,
            UUID uploadedBy) {

        assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Document content must not be empty");
        }
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported MIME type: " + mimeType
                + ". Supported: " + SUPPORTED_MIME_TYPES);
        }

        String safeFileName = safeFileName(fileName);
        AssetDocument doc = new AssetDocument();
        doc.setAssetId(assetId);
        doc.setDocumentType(type);
        doc.setSource(AssetDocumentSource.UPLOAD);
        doc.setMimeType(mimeType);
        doc.setFileName(safeFileName);
        doc.setContentHash(sha256Hex(content));
        doc.setSizeBytes((long) content.length);
        doc.setUploadedBy(uploadedBy);

        if (content.length <= MAX_INLINE_BYTES) {
            doc.setStorageRef(INLINE_STORAGE_REF);
            AssetDocument saved = assetDocumentRepository.save(doc);

            AssetDocumentContent inline = new AssetDocumentContent();
            inline.setId(saved.getId());
            inline.setContent(content);
            assetDocumentContentRepository.save(inline);

            log.info("Stored asset document inline: id={}, asset={}", saved.getId(), assetId);
            auditDocument("TERM_SHEET_UPLOADED", saved, uploadedBy);
            return saved;
        } else {
            String s3Key = "termsheets/" + assetId + "/" + UUID.randomUUID() + "/" + safeFileName;
            doc.setStorageRef(s3Key);
            AssetDocument saved = assetDocumentRepository.save(doc);

            uploadS3(s3Key, content, mimeType);

            log.info("Stored asset document in S3: id={}, key={}", saved.getId(), s3Key);
            auditDocument("TERM_SHEET_UPLOADED", saved, uploadedBy);
            return saved;
        }
    }

    /**
     * Lists all non-deleted documents for an asset.
     */
    @Transactional(readOnly = true)
    public List<AssetDocument> listDocuments(UUID assetId) {
        return assetDocumentRepository.findByAssetIdAndDeletedAtIsNull(assetId);
    }

    /**
     * Returns a single document by ID.
     */
    @Transactional(readOnly = true)
    public AssetDocument getDocument(UUID docId) {
        return assetDocumentRepository.findById(docId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDocument", docId));
    }

    @Transactional(readOnly = true)
    public AssetDocument getDocument(UUID assetId, UUID docId) {
        return assetDocumentRepository.findByIdAndAssetIdAndDeletedAtIsNull(docId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDocument", docId));
    }

    /**
     * Returns the raw content bytes for a document.
     * Fetches from inline storage or S3 transparently.
     *
     * @throws IllegalStateException if content has not yet been fetched (on-chain reference only)
     */
    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID docId) {
        AssetDocument doc = getDocument(docId);
        return getDocumentContent(doc);
    }

    @Transactional(readOnly = true)
    public byte[] getDocumentContent(UUID assetId, UUID docId) {
        return getDocumentContent(getDocument(assetId, docId));
    }

    private byte[] getDocumentContent(AssetDocument doc) {
        UUID docId = doc.getId();
        if (doc.getStorageRef() == null) {
            throw new IllegalStateException(
                "Document content not yet fetched from on-chain URI. Trigger /sync-from-chain first.");
        }
        if (INLINE_STORAGE_REF.equals(doc.getStorageRef())) {
            return assetDocumentContentRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDocumentContent", docId))
                .getContent();
        }
        return s3DocumentStorageAdapter.download(doc.getStorageRef());
    }

    /**
     * Soft-deletes a document.
     */
    public void softDeleteDocument(UUID assetId, UUID docId, UUID actorId) {
        AssetDocument doc = getDocument(assetId, docId);
        doc.setDeletedAt(Instant.now());
        assetDocumentRepository.save(doc);
        log.info("Soft-deleted asset document: id={}, by={}", docId, actorId);
        auditDocument("TERM_SHEET_DELETED", doc, actorId);
    }

    /**
     * Fetches the term sheet from the deployed token contract on-chain, downloads
     * the content from the returned URI, and persists it as an AssetDocument.
     *
     * @param deploymentId ID of the {@link de.makibytes.registerwerk.domain.asset.AssetDeployment}
     * @return the created or updated {@link AssetDocument}
     */
    public AssetDocument syncFromChain(UUID assetId, UUID deploymentId) {
        var deployment = assetDeploymentRepository.findByIdAndAssetId(deploymentId, assetId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));

        AssetDocument doc = onChainFetchService.fetch(deployment, this);
        auditDocument("TERM_SHEET_SYNCED", doc, null);
        return doc;
    }

    // ── Package-private helpers used by TermSheetOnChainFetchService ──────────

    AssetDocument saveDocument(AssetDocument doc) {
        return assetDocumentRepository.save(doc);
    }

    void saveContent(AssetDocumentContent content) {
        assetDocumentContentRepository.save(content);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void auditDocument(String eventType, AssetDocument doc, UUID actorId) {
        java.util.Map<String, Object> details = java.util.Map.of(
            "assetId", doc.getAssetId(), "documentType", doc.getDocumentType(),
            "source", doc.getSource(), "mimeType", doc.getMimeType());
        switch (eventType) {
            case "TERM_SHEET_UPLOADED" -> eventPublisher.publishEvent(new TermSheetUploadedEvent(doc.getId(), actorId, null, details));
            case "TERM_SHEET_DELETED"  -> eventPublisher.publishEvent(new TermSheetDeletedEvent(doc.getId(), actorId, null, details));
            case "TERM_SHEET_SYNCED"   -> eventPublisher.publishEvent(new TermSheetSyncedEvent(doc.getId(), actorId, null, details));
            default -> throw new IllegalArgumentException("Unknown term sheet event: " + eventType);
        }
    }

    static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String safeFileName(String fileName) {
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
                        log.error("Could not remove orphaned S3 term sheet after transaction rollback: key={}",
                                s3Key, cleanupFailure);
                    }
                }
            }
        });
    }

    void uploadS3(String s3Key, byte[] content, String mimeType) {
        registerS3RollbackCleanup(s3Key);
        s3DocumentStorageAdapter.upload(s3Key, content, mimeType);
    }
}
