package de.makibytes.registerwerk.asset.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_vault_state")
public class AssetVaultState {

    @Id
    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "underlying_asset_id")
    private UUID underlyingAssetId;

    @Column(name = "deposit_cap", precision = 78, scale = 0)
    private java.math.BigInteger depositCap;

    @Column(name = "min_settlement_delay")
    private Integer minSettlementDelay;

    @Column(name = "latest_nav_per_share", precision = 38, scale = 18)
    private BigDecimal latestNavPerShare;

    @Column(name = "latest_nav_strike_at")
    private Instant latestNavStrikeAt;

    @Column(name = "latest_nav_report_hash")
    private byte[] latestNavReportHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public UUID getUnderlyingAssetId() { return underlyingAssetId; }
    public void setUnderlyingAssetId(UUID underlyingAssetId) { this.underlyingAssetId = underlyingAssetId; }

    public java.math.BigInteger getDepositCap() { return depositCap; }
    public void setDepositCap(java.math.BigInteger depositCap) { this.depositCap = depositCap; }

    public Integer getMinSettlementDelay() { return minSettlementDelay; }
    public void setMinSettlementDelay(Integer minSettlementDelay) { this.minSettlementDelay = minSettlementDelay; }

    public BigDecimal getLatestNavPerShare() { return latestNavPerShare; }
    public void setLatestNavPerShare(BigDecimal latestNavPerShare) { this.latestNavPerShare = latestNavPerShare; }

    public Instant getLatestNavStrikeAt() { return latestNavStrikeAt; }
    public void setLatestNavStrikeAt(Instant latestNavStrikeAt) { this.latestNavStrikeAt = latestNavStrikeAt; }

    public byte[] getLatestNavReportHash() { return latestNavReportHash; }
    public void setLatestNavReportHash(byte[] latestNavReportHash) { this.latestNavReportHash = latestNavReportHash; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
