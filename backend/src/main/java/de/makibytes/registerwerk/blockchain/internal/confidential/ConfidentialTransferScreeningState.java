package de.makibytes.registerwerk.blockchain.internal.confidential;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-deployment sync cursor for {@link ConfidentialTravelRuleScreeningService} — tracks the
 * last on-chain block screened for Travel Rule obligations so each
 * scheduled run only decrypts and evaluates confidential transfer/mint events new since the
 * previous run, mirroring {@code indexer.api.IndexerState}'s cursor pattern but scoped per
 * confidential asset deployment rather than per (chain, indexer type).
 */
@Entity
@Table(
    name = "confidential_transfer_screening_state",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_conf_transfer_screening_deployment",
        columnNames = {"asset_deployment_id"}
    )
)
class ConfidentialTransferScreeningState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_deployment_id", nullable = false)
    private UUID assetDeploymentId;

    @Column(name = "last_screened_block", nullable = false)
    private long lastScreenedBlock = 0L;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "consecutive_decrypt_failures", nullable = false)
    private int consecutiveDecryptFailures = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getId() { return id; }

    UUID getAssetDeploymentId() { return assetDeploymentId; }
    void setAssetDeploymentId(UUID assetDeploymentId) { this.assetDeploymentId = assetDeploymentId; }

    long getLastScreenedBlock() { return lastScreenedBlock; }
    void setLastScreenedBlock(long lastScreenedBlock) { this.lastScreenedBlock = lastScreenedBlock; }

    Instant getLastRunAt() { return lastRunAt; }
    void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }

    String getLastError() { return lastError; }
    void setLastError(String lastError) { this.lastError = lastError; }

    int getConsecutiveDecryptFailures() { return consecutiveDecryptFailures; }
    void setConsecutiveDecryptFailures(int consecutiveDecryptFailures) { this.consecutiveDecryptFailures = consecutiveDecryptFailures; }
}
