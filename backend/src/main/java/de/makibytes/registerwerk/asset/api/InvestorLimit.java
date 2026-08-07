package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A per-investor override of an {@link Asset}'s default min-investment/max-holding limits
 * (F-BLOCKER-12) — e.g. a negotiated cornerstone-investor exception, or a lockup period on a
 * specific position. Absence of a row for an (asset, investor) pair means the asset's own
 * {@code minInvestmentAmount}/{@code maxHoldingAmount} defaults apply unmodified, and no lockup
 * is in effect.
 */
@Entity
@Table(name = "investor_limit", uniqueConstraints = @UniqueConstraint(columnNames = {"asset_id", "investor_entity_id"}))
public class InvestorLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "investor_entity_id", nullable = false)
    private UUID investorEntityId;

    @Column(name = "min_investment_override", precision = 38, scale = 8)
    private BigDecimal minInvestmentOverride;

    @Column(name = "max_holding_override", precision = 38, scale = 8)
    private BigDecimal maxHoldingOverride;

    /** No sale/transfer of this investor's holding in this asset is permitted before this date. */
    @Column(name = "lockup_until")
    private LocalDate lockupUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getInvestorEntityId() { return investorEntityId; }
    public void setInvestorEntityId(UUID investorEntityId) { this.investorEntityId = investorEntityId; }

    public BigDecimal getMinInvestmentOverride() { return minInvestmentOverride; }
    public void setMinInvestmentOverride(BigDecimal minInvestmentOverride) { this.minInvestmentOverride = minInvestmentOverride; }

    public BigDecimal getMaxHoldingOverride() { return maxHoldingOverride; }
    public void setMaxHoldingOverride(BigDecimal maxHoldingOverride) { this.maxHoldingOverride = maxHoldingOverride; }

    public LocalDate getLockupUntil() { return lockupUntil; }
    public void setLockupUntil(LocalDate lockupUntil) { this.lockupUntil = lockupUntil; }

    public Instant getUpdatedAt() { return updatedAt; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
