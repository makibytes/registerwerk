package de.makibytes.registerwerk.marketplace.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One manifest version of a listing. {@code manifestRaw} holds the verbatim bytes the
 * publisher signed — it is the keccak256 hash input and must never be normalized;
 * {@code manifestJson} is a parsed copy for querying only.
 */
@Entity
@Table(
    name = "dapp_version",
    uniqueConstraints = @UniqueConstraint(name = "uq_dapp_version", columnNames = {"listing_id", "version"})
)
public class DappVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "version", nullable = false, length = 40)
    private String version;

    @Column(name = "manifest_raw", columnDefinition = "text")
    private String manifestRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", columnDefinition = "jsonb")
    private String manifestJson;

    @Column(name = "manifest_hash", length = 66)
    private String manifestHash;

    @Column(name = "manifest_signature", length = 200)
    private String manifestSignature;

    @Column(name = "signer_wallet", length = 66)
    private String signerWallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DappVersionStatus status = DappVersionStatus.DRAFT;

    @Column(name = "review_notes")
    private String reviewNotes;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "onchain_tx", length = 66)
    private String onchainTx;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getListingId() { return listingId; }
    public void setListingId(UUID listingId) { this.listingId = listingId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getManifestRaw() { return manifestRaw; }
    public void setManifestRaw(String manifestRaw) { this.manifestRaw = manifestRaw; }

    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }

    public String getManifestHash() { return manifestHash; }
    public void setManifestHash(String manifestHash) { this.manifestHash = manifestHash; }

    public String getManifestSignature() { return manifestSignature; }
    public void setManifestSignature(String manifestSignature) { this.manifestSignature = manifestSignature; }

    public String getSignerWallet() { return signerWallet; }
    public void setSignerWallet(String signerWallet) { this.signerWallet = signerWallet; }

    public DappVersionStatus getStatus() { return status; }
    public void setStatus(DappVersionStatus status) { this.status = status; }

    public String getReviewNotes() { return reviewNotes; }
    public void setReviewNotes(String reviewNotes) { this.reviewNotes = reviewNotes; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getOnchainTx() { return onchainTx; }
    public void setOnchainTx(String onchainTx) { this.onchainTx = onchainTx; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
