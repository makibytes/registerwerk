package de.makibytes.registerwerk.lending.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered {@code EwpgRepoMarket} instance (see {@code contracts/src/lending/EwpgRepoMarket.sol}).
 * Operator-entered metadata, not auto-discovered from chain — mirrors how {@code asset_deployment}
 * rows are created after a deployment completes. {@link de.makibytes.registerwerk.lending.internal.RepoMarketOnchainReader}
 * is the only thing that ever reads the market's own mutable state (reserve factor, pause flag)
 * live; the immutable parameters cached here (lltvBps, rate curve, …) never drift from chain
 * since the contract itself makes them immutable.
 */
@Entity
@Table(name = "lending_market")
public class LendingMarket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    @NotNull
    private UUID chainConfigId;

    @Column(name = "market_address", nullable = false, length = 66)
    @NotBlank
    private String marketAddress;

    @Column(name = "vault_address", length = 66)
    private String vaultAddress;

    /** Links to the collateral security's {@code asset} row — the jurisdiction lookup path. */
    @Column(name = "collateral_asset_id")
    private UUID collateralAssetId;

    @Column(name = "collateral_token_address", nullable = false, length = 66)
    @NotBlank
    private String collateralTokenAddress;

    @Column(name = "loan_token_address", nullable = false, length = 66)
    @NotBlank
    private String loanTokenAddress;

    /** Optional link to {@code payment_rail.code} for the loan-token leg (e.g. "aueur"). */
    @Column(name = "loan_rail_code", length = 60)
    private String loanRailCode;

    @Column(name = "lltv_bps", nullable = false)
    @NotNull
    private Integer lltvBps;

    /** Maximum origination LTV. Deliberately lower than {@link #lltvBps}. */
    @Column(name = "max_ltv_bps")
    private Integer maxLtvBps;

    @Column(name = "liquidation_bonus_bps", nullable = false)
    @NotNull
    private Integer liquidationBonusBps;

    @Column(name = "base_rate_wad", nullable = false, precision = 38, scale = 0)
    @NotNull
    private BigInteger baseRateWad;

    @Column(name = "slope_wad", nullable = false, precision = 38, scale = 0)
    @NotNull
    private BigInteger slopeWad;

    @Column(name = "max_price_age_seconds", precision = 78, scale = 0)
    private BigInteger maxPriceAgeSeconds;

    @Column(name = "liquidation_grace_period_seconds", precision = 78, scale = 0)
    private BigInteger liquidationGracePeriodSeconds;

    @Column(name = "loan_token_decimals")
    private Integer loanTokenDecimals;

    @Column(name = "price_oracle_address", nullable = false, length = 66)
    @NotBlank
    private String priceOracleAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull
    private LendingMarketStatus status = LendingMarketStatus.ACTIVE;

    @Column(name = "registered_by")
    private UUID registeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getMarketAddress() { return marketAddress; }
    public void setMarketAddress(String marketAddress) { this.marketAddress = marketAddress; }

    public String getVaultAddress() { return vaultAddress; }
    public void setVaultAddress(String vaultAddress) { this.vaultAddress = vaultAddress; }

    public UUID getCollateralAssetId() { return collateralAssetId; }
    public void setCollateralAssetId(UUID collateralAssetId) { this.collateralAssetId = collateralAssetId; }

    public String getCollateralTokenAddress() { return collateralTokenAddress; }
    public void setCollateralTokenAddress(String collateralTokenAddress) { this.collateralTokenAddress = collateralTokenAddress; }

    public String getLoanTokenAddress() { return loanTokenAddress; }
    public void setLoanTokenAddress(String loanTokenAddress) { this.loanTokenAddress = loanTokenAddress; }

    public String getLoanRailCode() { return loanRailCode; }
    public void setLoanRailCode(String loanRailCode) { this.loanRailCode = loanRailCode; }

    public Integer getLltvBps() { return lltvBps; }
    public void setLltvBps(Integer lltvBps) { this.lltvBps = lltvBps; }

    public Integer getMaxLtvBps() { return maxLtvBps; }
    public void setMaxLtvBps(Integer maxLtvBps) { this.maxLtvBps = maxLtvBps; }

    public Integer getLiquidationBonusBps() { return liquidationBonusBps; }
    public void setLiquidationBonusBps(Integer liquidationBonusBps) { this.liquidationBonusBps = liquidationBonusBps; }

    public BigInteger getBaseRateWad() { return baseRateWad; }
    public void setBaseRateWad(BigInteger baseRateWad) { this.baseRateWad = baseRateWad; }

    public BigInteger getSlopeWad() { return slopeWad; }
    public void setSlopeWad(BigInteger slopeWad) { this.slopeWad = slopeWad; }

    public BigInteger getMaxPriceAgeSeconds() { return maxPriceAgeSeconds; }
    public void setMaxPriceAgeSeconds(BigInteger maxPriceAgeSeconds) { this.maxPriceAgeSeconds = maxPriceAgeSeconds; }

    public BigInteger getLiquidationGracePeriodSeconds() { return liquidationGracePeriodSeconds; }
    public void setLiquidationGracePeriodSeconds(BigInteger liquidationGracePeriodSeconds) { this.liquidationGracePeriodSeconds = liquidationGracePeriodSeconds; }

    public Integer getLoanTokenDecimals() { return loanTokenDecimals; }
    public void setLoanTokenDecimals(Integer loanTokenDecimals) { this.loanTokenDecimals = loanTokenDecimals; }

    public String getPriceOracleAddress() { return priceOracleAddress; }
    public void setPriceOracleAddress(String priceOracleAddress) { this.priceOracleAddress = priceOracleAddress; }

    public LendingMarketStatus getStatus() { return status; }
    public void setStatus(LendingMarketStatus status) { this.status = status; }

    public UUID getRegisteredBy() { return registeredBy; }
    public void setRegisteredBy(UUID registeredBy) { this.registeredBy = registeredBy; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
