package de.makibytes.registerwerk.blockchain.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-deployment log-scan cursor for {@link VaultRequestIngestionService}: the highest block whose
 * ERC-7540 request-lifecycle logs have been ingested into {@code vault_request}. Only ever
 * advanced to a FINALIZED block, in the same transaction as the rows that range produced.
 */
@Entity
@Table(name = "vault_request_ingest_cursor")
class VaultRequestIngestCursor {

    @Id
    @Column(name = "asset_deployment_id", nullable = false)
    private UUID assetDeploymentId;

    @Column(name = "last_scanned_block", nullable = false)
    private long lastScannedBlock;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VaultRequestIngestCursor() {}

    VaultRequestIngestCursor(UUID assetDeploymentId, long lastScannedBlock) {
        this.assetDeploymentId = assetDeploymentId;
        this.lastScannedBlock = lastScannedBlock;
    }

    UUID getAssetDeploymentId() { return assetDeploymentId; }

    long getLastScannedBlock() { return lastScannedBlock; }

    void advanceTo(long block) {
        this.lastScannedBlock = block;
        this.lastRunAt = Instant.now();
        this.lastError = null;
        this.updatedAt = this.lastRunAt;
    }

    String getLastError() { return lastError; }

    void recordError(String error) {
        this.lastRunAt = Instant.now();
        this.lastError = error;
        this.updatedAt = this.lastRunAt;
    }
}
