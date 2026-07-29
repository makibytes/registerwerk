package de.makibytes.registerwerk.lending.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Cached lender (supply-side) position for one (market, wallet) pair — a snapshot of the last
 * live {@code EwpgRepoMarket.balanceOf(lender)} read. See {@link LendingPosition} for the same
 * "cache, not ledger" caveat.
 */
@Entity
@Table(name = "lending_supply_position")
public class LendingSupplyPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "market_id", nullable = false)
    @NotNull
    private UUID marketId;

    @Column(name = "wallet_address", nullable = false, length = 66)
    @NotBlank
    private String walletAddress;

    @Column(name = "current_claim", nullable = false, precision = 78, scale = 0)
    @NotNull
    private BigInteger currentClaim = BigInteger.ZERO;

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

    public BigInteger getCurrentClaim() { return currentClaim; }
    public void setCurrentClaim(BigInteger currentClaim) { this.currentClaim = currentClaim; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
