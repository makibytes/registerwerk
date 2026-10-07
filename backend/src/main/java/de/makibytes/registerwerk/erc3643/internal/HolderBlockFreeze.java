package de.makibytes.registerwerk.erc3643.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Whether one §16 eWpG Sperrvermerk actually reached one token deployment for one wallet (H5). One row per
 * (block, deployment, wallet); the register-level block is authoritative whatever this row says, the row only
 * records how far the chain follows it and what is still outstanding. See {@code V51__holder_block_freeze_outcome.sql}
 * for the meaning of each status.
 */
@Entity
@Table(name = "holder_block_freeze")
class HolderBlockFreeze {

    enum Status {
        SUBMITTED, CONFIRMED, FAILED, UNSUPPORTED_ON_CHAIN, RELEASE_SUBMITTED, RELEASED, RELEASE_FAILED;

        /** The wallet may still move on-chain although the register blocks it (needs a human or a retry). */
        boolean isOutstanding() {
            return this == FAILED || this == UNSUPPORTED_ON_CHAIN;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "holder_block_id", nullable = false)
    private UUID holderBlockId;

    @Column(name = "deployment_id", nullable = false)
    private UUID deploymentId;

    @Column(name = "wallet_address", nullable = false)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "tx_hash", length = 128)
    private String txHash;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "drift_count", nullable = false)
    private int driftCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    HolderBlockFreeze() {
    }

    HolderBlockFreeze(UUID holderBlockId, UUID deploymentId, String walletAddress) {
        this.holderBlockId = holderBlockId;
        this.deploymentId = deploymentId;
        this.walletAddress = walletAddress;
    }

    UUID getId() { return id; }
    UUID getHolderBlockId() { return holderBlockId; }
    UUID getDeploymentId() { return deploymentId; }
    String getWalletAddress() { return walletAddress; }
    Status getStatus() { return status; }
    UUID getTxId() { return txId; }
    String getTxHash() { return txHash; }
    String getDetail() { return detail; }
    int getAttempts() { return attempts; }
    int getDriftCount() { return driftCount; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    Instant getConfirmedAt() { return confirmedAt; }
    Instant getVerifiedAt() { return verifiedAt; }

    void setId(UUID id) { this.id = id; }

    /** Moves to {@code status}, stamping the change time. */
    void transitionTo(Status status, String detail) {
        this.status = status;
        this.detail = detail;
        this.updatedAt = Instant.now();
        if (status == Status.CONFIRMED) {
            this.confirmedAt = this.updatedAt;
        }
    }

    /** Records a freshly submitted transaction (freeze or release). */
    void submitted(Status status, UUID txId, String txHash) {
        this.txId = txId;
        this.txHash = txHash;
        this.attempts++;
        transitionTo(status, null);
    }

    /** A freeze or release that could not be submitted or failed on-chain. */
    void failed(Status status, String detail) {
        this.attempts++;
        transitionTo(status, detail);
    }

    void setTxHash(String txHash) { this.txHash = txHash; }

    void verifiedNow() {
        this.verifiedAt = Instant.now();
    }

    void drifted() {
        this.driftCount++;
        this.updatedAt = Instant.now();
    }
}
