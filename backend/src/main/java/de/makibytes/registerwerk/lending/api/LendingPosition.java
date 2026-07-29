package de.makibytes.registerwerk.lending.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Cached borrower position for one (market, wallet) pair — a snapshot of the last live
 * {@code EwpgRepoMarket.positions}/{@code debtOf}/{@code healthFactor} read, refreshed by
 * {@link de.makibytes.registerwerk.lending.internal.LendingPositionService} whenever the owning
 * wallet's positions are requested. This is a cache, not a ledger: the contract's own state is
 * always authoritative, and a stale row here can never cause an incorrect on-chain outcome
 * (borrowing/repaying/liquidating always re-reads the contract at call time via the wallet's own
 * transaction).
 */
@Entity
@Table(name = "lending_position")
public class LendingPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "market_id", nullable = false)
    @NotNull
    private UUID marketId;

    @Column(name = "wallet_address", nullable = false, length = 66)
    @NotBlank
    private String walletAddress;

    @Column(name = "collateral_amount", nullable = false, precision = 78, scale = 0)
    @NotNull
    private BigInteger collateralAmount = BigInteger.ZERO;

    @Column(name = "current_debt", nullable = false, precision = 78, scale = 0)
    @NotNull
    private BigInteger currentDebt = BigInteger.ZERO;

    /** WAD-scaled (1e18 = 1.0); null when {@code currentDebt} is zero ("infinite" on-chain). */
    @Column(name = "health_factor_wad", precision = 78, scale = 0)
    private BigInteger healthFactorWad;

    /**
     * Whether {@link #healthFactorWad} was computed from a fresh, priced mark — null when
     * {@code healthFactorWad} itself is null (no debt, or the read
     * failed); false means the contract's own {@code priceReliable} flag was false (unpriced or
     * stale collateral) and {@code healthFactorWad} must not be treated as a trustworthy risk
     * signal.
     */
    @Column(name = "health_factor_reliable")
    private Boolean healthFactorReliable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull
    private LendingPositionStatus status = LendingPositionStatus.OPEN;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMarketId() { return marketId; }
    public void setMarketId(UUID marketId) { this.marketId = marketId; }

    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public BigInteger getCollateralAmount() { return collateralAmount; }
    public void setCollateralAmount(BigInteger collateralAmount) { this.collateralAmount = collateralAmount; }

    public BigInteger getCurrentDebt() { return currentDebt; }
    public void setCurrentDebt(BigInteger currentDebt) { this.currentDebt = currentDebt; }

    public BigInteger getHealthFactorWad() { return healthFactorWad; }
    public void setHealthFactorWad(BigInteger healthFactorWad) { this.healthFactorWad = healthFactorWad; }

    public Boolean getHealthFactorReliable() { return healthFactorReliable; }
    public void setHealthFactorReliable(Boolean healthFactorReliable) { this.healthFactorReliable = healthFactorReliable; }

    public LendingPositionStatus getStatus() { return status; }
    public void setStatus(LendingPositionStatus status) { this.status = status; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
