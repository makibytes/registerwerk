package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Delegatable ASSET_TOKEN_ADMIN capability — gates forcedTransfer/forcedApprove/forceBurn
 * (eWpG §24/§26-style registry-compulsory corrections) beyond REGISTRY_ADMIN. By default
 * nobody has this, not even an asset's own issuer (see {@code asset.web.AssetAccessChecker
 * #canForceAdmin}); an operator explicitly grants it to a customer entity, either scoped to
 * one asset ({@code assetId} set) or entity-wide across all of that entity's present/future
 * assets ({@code assetId} NULL).
 *
 * <p>Structural analogue of {@code kyc.api.HolderBlock} (Sperrvermerk) — same lifecycle
 * shape (create/revoke, legal basis, 4-eyes, auto-expiry).
 */
@Entity
@Table(name = "asset_token_admin_grant")
public class AssetTokenAdminGrant {

    /** Extensible — ASSET_TOKEN_ADMIN is the only delegatable capability today. */
    public enum Capability {
        ASSET_TOKEN_ADMIN
    }

    public enum Status {
        ACTIVE, REVOKED, EXPIRED
    }

    /** Which check justified the grant at creation time — recorded for audit, not re-checked at call time. */
    public enum EligibilityBasis {
        ISSUER_WALLET_BINDING,
        INVESTOR_WHITELIST,
        INVESTOR_WHITELIST_AND_ONCHAINID,
        ENTITY_WALLET_BINDING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** NULL means entity-wide — applies across every asset where entityId is issuer or holder. */
    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 40)
    private Capability capability = Capability.ASSET_TOKEN_ADMIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_basis", nullable = false, length = 40)
    private EligibilityBasis eligibilityBasis;

    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @Column(name = "legal_basis", nullable = false)
    private String legalBasis;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "dual_control_approver_id")
    private UUID dualControlApproverId;

    @Column(name = "dual_control_approved_at")
    private Instant dualControlApprovedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "revoke_dual_control_approver_id")
    private UUID revokeDualControlApproverId;

    @Column(name = "revoke_dual_control_approved_at")
    private Instant revokeDualControlApprovedAt;

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
    public Capability getCapability() { return capability; }
    public void setCapability(Capability v) { this.capability = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; updatedAt = Instant.now(); }
    public EligibilityBasis getEligibilityBasis() { return eligibilityBasis; }
    public void setEligibilityBasis(EligibilityBasis v) { this.eligibilityBasis = v; }
    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID v) { this.chainConfigId = v; }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String v) { this.legalBasis = v; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }
    public UUID getDualControlApproverId() { return dualControlApproverId; }
    public void setDualControlApproverId(UUID v) { this.dualControlApproverId = v; }
    public Instant getDualControlApprovedAt() { return dualControlApprovedAt; }
    public void setDualControlApprovedAt(Instant v) { this.dualControlApprovedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public UUID getRevokedBy() { return revokedBy; }
    public void setRevokedBy(UUID v) { this.revokedBy = v; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String v) { this.revokeReason = v; }
    public UUID getRevokeDualControlApproverId() { return revokeDualControlApproverId; }
    public void setRevokeDualControlApproverId(UUID v) { this.revokeDualControlApproverId = v; }
    public Instant getRevokeDualControlApprovedAt() { return revokeDualControlApprovedAt; }
    public void setRevokeDualControlApprovedAt(Instant v) { this.revokeDualControlApprovedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
