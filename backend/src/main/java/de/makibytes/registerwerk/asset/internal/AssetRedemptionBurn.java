package de.makibytes.registerwerk.asset.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One burn a redemption dispatched (Wave 0b C7): wallet W on deployment D loses {@code amount} units. The row is the
 * idempotency key ({@code UNIQUE(asset, deployment, wallet)}: a redelivered redemption event finds it and never burns
 * twice) and tracks the burn to its outcome - SUBMITTED until the burn transaction is final and its BURN transfer is
 * indexed, then CONFIRMED; FAILED when it reverted, was replaced, or could not be submitted at all (the failure reason
 * says which), retryable by resuming the redemption.
 */
@Entity
@Table(name = "asset_redemption_burn")
class AssetRedemptionBurn {

    enum Status { SUBMITTED, CONFIRMED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "deployment_id", nullable = false)
    private UUID deploymentId;

    @Column(name = "wallet_address", nullable = false, length = 128)
    private String walletAddress;

    @Column(name = "amount", nullable = false, precision = 96, scale = 18)
    private BigDecimal amount;

    @Column(name = "tx_id")
    private UUID txId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.SUBMITTED;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    UUID getId() { return id; }
    UUID getAssetId() { return assetId; }
    void setAssetId(UUID assetId) { this.assetId = assetId; }
    UUID getDeploymentId() { return deploymentId; }
    void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }
    String getWalletAddress() { return walletAddress; }
    void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }
    BigDecimal getAmount() { return amount; }
    void setAmount(BigDecimal amount) { this.amount = amount; }
    UUID getTxId() { return txId; }
    void setTxId(UUID txId) { this.txId = txId; }
    Status getStatus() { return status; }
    void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    String getFailureReason() { return failureReason; }
    void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    Instant getConfirmedAt() { return confirmedAt; }
    void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    Instant getCreatedAt() { return createdAt; }
}
