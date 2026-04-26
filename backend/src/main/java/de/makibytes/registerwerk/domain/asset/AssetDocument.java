package de.makibytes.registerwerk.domain.asset;

import de.makibytes.registerwerk.domain.enums.AssetDocumentSource;
import de.makibytes.registerwerk.domain.enums.AssetDocumentType;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_document")
public class AssetDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private AssetDocumentType documentType = AssetDocumentType.TERM_SHEET;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private AssetDocumentSource source = AssetDocumentSource.UPLOAD;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_name", length = 500)
    private String fileName;

    /** "inline" when content is in asset_document_content; S3 key otherwise; null if not yet fetched. */
    @Column(name = "storage_ref", length = 1000)
    private String storageRef;

    /** SHA-256 hex for uploads; keccak256 hex for on-chain verified content. */
    @Column(name = "content_hash", length = 66)
    private String contentHash;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "chain", length = 20)
    private Chain chain;

    @Enumerated(EnumType.STRING)
    @Column(name = "network", length = 10)
    private Network network;

    /** bytes32 document name as 0x-prefixed hex string. */
    @Column(name = "onchain_doc_name", length = 66)
    private String onchainDocName;

    /** URI as returned by the on-chain contract. */
    @Column(name = "onchain_uri", length = 2000)
    private String onchainUri;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    /** Timestamp when content was last fetched from onchainUri. */
    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public AssetDocumentType getDocumentType() { return documentType; }
    public void setDocumentType(AssetDocumentType documentType) { this.documentType = documentType; }

    public AssetDocumentSource getSource() { return source; }
    public void setSource(AssetDocumentSource source) { this.source = source; }

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

    public Chain getChain() { return chain; }
    public void setChain(Chain chain) { this.chain = chain; }

    public Network getNetwork() { return network; }
    public void setNetwork(Network network) { this.network = network; }

    public String getOnchainDocName() { return onchainDocName; }
    public void setOnchainDocName(String onchainDocName) { this.onchainDocName = onchainDocName; }

    public String getOnchainUri() { return onchainUri; }
    public void setOnchainUri(String onchainUri) { this.onchainUri = onchainUri; }

    public Instant getUploadedAt() { return uploadedAt; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public boolean isDeleted() { return deletedAt != null; }
    public boolean hasContent() { return storageRef != null; }
}
