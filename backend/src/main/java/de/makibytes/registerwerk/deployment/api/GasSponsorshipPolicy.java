package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configures who pays gas for a customer's sponsored (ERC-4337) transactions against a given
 * asset, and how much. Mirrors the on-chain {@code EwpgPaymaster} policy budget
 * (contracts/src/ecosystem/EwpgPaymaster.sol) — the on-chain {@code policyId} is
 * {@code keccak256(id.toString())} for this row's {@link #id}.
 *
 * <p>Exactly one of {@link #assetDeploymentId} or {@link #issuerId} is expected to be set:
 * a deployment-scoped policy overrides an issuer's default for that one asset; an
 * issuer-scoped policy (assetDeploymentId null) is the default new deployments from that
 * issuer inherit unless they get their own override row.
 */
@Entity
@Table(name = "gas_sponsorship_policy")
public class GasSponsorshipPolicy {

    public enum Sponsor { OPERATOR, ISSUER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Deployment-scoped override. Null for an issuer-level default (see {@link #issuerId}). */
    @Column(name = "asset_deployment_id")
    private UUID assetDeploymentId;

    /** Issuer (legal entity) this default applies to when {@link #assetDeploymentId} is null. */
    @Column(name = "issuer_id")
    private UUID issuerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Sponsor sponsor;

    /** Monthly sponsorship budget, in ETH (native gas token), matching {@code MintControlRule}'s decimal-amount convention. */
    @Column(name = "monthly_cap_eth", precision = 38, scale = 18)
    private BigDecimal monthlyCapEth;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAssetDeploymentId() { return assetDeploymentId; }
    public void setAssetDeploymentId(UUID assetDeploymentId) { this.assetDeploymentId = assetDeploymentId; }

    public UUID getIssuerId() { return issuerId; }
    public void setIssuerId(UUID issuerId) { this.issuerId = issuerId; }

    public Sponsor getSponsor() { return sponsor; }
    public void setSponsor(Sponsor sponsor) { this.sponsor = sponsor; }

    public BigDecimal getMonthlyCapEth() { return monthlyCapEth; }
    public void setMonthlyCapEth(BigDecimal monthlyCapEth) { this.monthlyCapEth = monthlyCapEth; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
