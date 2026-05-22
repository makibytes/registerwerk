package de.makibytes.registerwerk.kyc.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * §16 eWpG Sperrvermerk — registry-layer legal block on a holder wallet.
 * Independent of on-chain frozen flags. Required for court orders, pledges,
 * insolvency proceedings, etc. All creates/lifts require 4-eyes approval.
 */
@Entity
@Table(name = "holder_block")
public class HolderBlock {

    public enum BlockType {
        PFANDRECHT, PFAENDUNG, GERICHTSBESCHLUSS, NACHLASSSPERRE,
        VERFUGUNGSVERBOT, INSOLVENZ, TOD, REGULATORISCH
    }

    public enum Status {
        ACTIVE, LIFTED, EXPIRED, SUPERSEDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 30)
    private BlockType blockType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "legal_basis", nullable = false)
    private String legalBasis;

    @Column(name = "court_ref")
    private String courtRef;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private UUID liftedBy;

    @Column(name = "lift_reason")
    private String liftReason;

    @Column(name = "on_chain_freeze_tx_hash")
    private String onChainFreezeTxHash;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID v) { this.entityId = v; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID v) { this.assetId = v; }
    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String v) { this.walletAddress = v; }
    public BlockType getBlockType() { return blockType; }
    public void setBlockType(BlockType v) { this.blockType = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; updatedAt = Instant.now(); }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String v) { this.legalBasis = v; }
    public String getCourtRef() { return courtRef; }
    public void setCourtRef(String v) { this.courtRef = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant v) { this.startsAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getLiftedAt() { return liftedAt; }
    public void setLiftedAt(Instant v) { this.liftedAt = v; }
    public UUID getLiftedBy() { return liftedBy; }
    public void setLiftedBy(UUID v) { this.liftedBy = v; }
    public String getLiftReason() { return liftReason; }
    public void setLiftReason(String v) { this.liftReason = v; }
    public String getOnChainFreezeTxHash() { return onChainFreezeTxHash; }
    public void setOnChainFreezeTxHash(String v) { this.onChainFreezeTxHash = v; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }
    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID v) { this.dualControlApproverId = v; }
    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant v) { this.dualControlApprovedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
