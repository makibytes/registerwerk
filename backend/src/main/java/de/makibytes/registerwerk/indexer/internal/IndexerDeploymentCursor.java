package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-deployment ingestion cursor (P4-08 Stage 1). {@code indexer_state} is keyed by
 * (chain, indexer type), which forced Stellar to share one minimum cursor across every watched
 * issuer account: a deployment added after the cursor advanced was never scanned from its own
 * beginning. Here every deployment owns its position and its "last successfully synced" evidence.
 */
@Entity
@Table(name = "indexer_deployment_cursor",
        uniqueConstraints = @UniqueConstraint(name = "uq_indexer_deployment_cursor",
                columnNames = {"deployment_id", "indexer_type"}))
class IndexerDeploymentCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deployment_id", nullable = false)
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "indexer_type", nullable = false, length = 30)
    private IndexerState.IndexerType indexerType;

    @Column(name = "cursor_value", length = 200)
    private String cursorValue;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getDeploymentId() { return deploymentId; }
    void setDeploymentId(UUID deploymentId) { this.deploymentId = deploymentId; }
    IndexerState.IndexerType getIndexerType() { return indexerType; }
    void setIndexerType(IndexerState.IndexerType indexerType) { this.indexerType = indexerType; }
    String getCursorValue() { return cursorValue; }
    void setCursorValue(String cursorValue) { this.cursorValue = cursorValue; }
    Instant getLastSyncedAt() { return lastSyncedAt; }
    void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}
