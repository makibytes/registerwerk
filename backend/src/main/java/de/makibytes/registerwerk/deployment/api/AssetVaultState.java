package de.makibytes.registerwerk.deployment.api;

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

    /**
     * Identity of the confirmed strike which owns the denormalized {@code latest_nav_*}
     * projection.  {@link #latestNavStrikeAt} is business time and is not unique: two distinct
     * on-chain strikes may deliberately share it.
     */
    @Column(name = "latest_nav_strike_id")
    private UUID latestNavStrikeId;

    @Column(name = "latest_nav_report_hash")
    private byte[] latestNavReportHash;

    /** In-flight {@code setDepositCap} value, not yet confirmed on-chain — {@link
     *  #depositCapTxHash} being non-null is itself the "pending" signal (there is no separate
     *  boolean, unlike {@code vault_nav_strike}/{@code vault_request}: this table has nothing
     *  else to poll concurrently, so nulling these four fields on confirmation is unambiguous).
     *  {@link #depositCap} itself is only ever updated once confirmed. */
    @Column(name = "pending_deposit_cap", precision = 78, scale = 0)
    private java.math.BigInteger pendingDepositCap;

    @Column(name = "deposit_cap_tx_hash", length = 80)
    private String depositCapTxHash;

    @Column(name = "deposit_cap_chain_config_id")
    private UUID depositCapChainConfigId;

    @Column(name = "deposit_cap_block_number")
    private Long depositCapBlockNumber;

    /** Exact block occurrence which established {@link #depositCap}. */
    @Column(name = "deposit_cap_block_hash", length = 128)
    private String depositCapBlockHash;

    /** Transaction whose canonical occurrence established {@link #depositCap}. */
    @Column(name = "deposit_cap_confirmed_tx_hash", length = 80)
    private String depositCapConfirmedTxHash;

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

    public UUID getLatestNavStrikeId() { return latestNavStrikeId; }
    public void setLatestNavStrikeId(UUID latestNavStrikeId) { this.latestNavStrikeId = latestNavStrikeId; }

    public byte[] getLatestNavReportHash() { return latestNavReportHash; }
    public void setLatestNavReportHash(byte[] latestNavReportHash) { this.latestNavReportHash = latestNavReportHash; }

    public java.math.BigInteger getPendingDepositCap() { return pendingDepositCap; }
    public void setPendingDepositCap(java.math.BigInteger pendingDepositCap) { this.pendingDepositCap = pendingDepositCap; }

    public String getDepositCapTxHash() { return depositCapTxHash; }
    public void setDepositCapTxHash(String depositCapTxHash) { this.depositCapTxHash = depositCapTxHash; }

    public UUID getDepositCapChainConfigId() { return depositCapChainConfigId; }
    public void setDepositCapChainConfigId(UUID depositCapChainConfigId) { this.depositCapChainConfigId = depositCapChainConfigId; }

    public Long getDepositCapBlockNumber() { return depositCapBlockNumber; }
    public void setDepositCapBlockNumber(Long depositCapBlockNumber) { this.depositCapBlockNumber = depositCapBlockNumber; }

    public String getDepositCapBlockHash() { return depositCapBlockHash; }
    public void setDepositCapBlockHash(String depositCapBlockHash) { this.depositCapBlockHash = depositCapBlockHash; }

    public String getDepositCapConfirmedTxHash() { return depositCapConfirmedTxHash; }
    public void setDepositCapConfirmedTxHash(String depositCapConfirmedTxHash) {
        this.depositCapConfirmedTxHash = depositCapConfirmedTxHash;
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
