package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "kyc_document")
public class KycDocument {

    public enum DocumentType {
        // ── Legacy values (kept for backward compatibility) ───────────────
        PASSPORT,
        COMMERCIAL_REGISTER,
        ANNUAL_REPORT,
        OWNERSHIP_CHART,
        AML_QUESTIONNAIRE,
        OTHER,
        // ── Extended EU-AMLD / jurisdiction-specific types ────────────────
        CERTIFICATE_OF_INCORPORATION,
        COMMERCIAL_REGISTER_EXTRACT,
        UBO_DECLARATION,
        BENEFICIAL_OWNER_REGISTER_EXTRACT,
        OWNERSHIP_STRUCTURE_CHART,
        SHAREHOLDER_REGISTER,
        BOARD_RESOLUTION,
        POWER_OF_ATTORNEY,
        IDENTITY_DOCUMENT,
        PROOF_OF_RESIDENCE,
        BANK_STATEMENT,
        SOURCE_OF_FUNDS,
        LEI_CERTIFICATE,
        REGULATORY_LICENSE,
        TOKEN_WHITEPAPER,
        SMART_CONTRACT_AUDIT,
        PEP_SANCTIONS_SCREENING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 'inline' means content is in kyc_document_content; otherwise this is an S3 object key. */
    @Column(name = "storage_ref", nullable = false, length = 1000)
    private String storageRef;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    /** Optional jurisdiction scope. Null means the document is universal (counts for any jurisdiction). */
    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", length = 20)
    private Jurisdiction jurisdiction;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getLegalEntityId() { return legalEntityId; }
    public void setLegalEntityId(UUID legalEntityId) { this.legalEntityId = legalEntityId; }

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getUploadedAt() { return uploadedAt; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate expiresAt) { this.expiresAt = expiresAt; }

    public Jurisdiction getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(Jurisdiction jurisdiction) { this.jurisdiction = jurisdiction; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public boolean isDeleted() { return deletedAt != null; }
}
