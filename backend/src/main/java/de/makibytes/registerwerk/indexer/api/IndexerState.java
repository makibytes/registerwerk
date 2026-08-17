package de.makibytes.registerwerk.indexer.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the sync progress and health of a single indexer instance for one (chain, type) pair.
 * Used by scheduled jobs to determine the starting point for the next sync window and to
 * detect stale / errored indexers.
 */
@Entity
@Table(
    name = "indexer_state",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_indexer_state_chain_type",
        columnNames = {"chain_config_id", "indexer_type"}
    )
)
public class IndexerState {

    public enum IndexerType { GRAPH_NODE, SOLANA_GEYSER, SOLANA_POLL, CANTON_STREAM, STARKNET_POLL, STELLAR_HORIZON }

    public enum IndexerStatus { ACTIVE, PAUSED, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Enumerated(EnumType.STRING)
    @Column(name = "indexer_type", nullable = false, length = 30)
    private IndexerType indexerType;

    /** Last successfully processed block number (EVM or Starknet). Null before first sync.
     *  Head/provisional cursor — the highest block read from at all, FINAL or still PROVISIONAL. */
    @Column(name = "last_synced_block")
    private Long lastSyncedBlock;

    /**
     * Highest block number whose token_transfer rows have all been re-verified and flipped to
     * FINAL. Always {@code <= lastSyncedBlock}. Null before the first successful finality pass,
     * or for indexer types with no two-tier model. See V4__reorg_safety.sql for the full
     * rationale.
     */
    @Column(name = "last_final_block")
    private Long lastFinalBlock;

    /**
     * Last successfully processed cursor as a string: Solana transaction signature, Canton
     * ledger offset, or Stellar Horizon paging token. Null before first sync.
     */
    @Column(name = "last_synced_signature", length = 200)
    private String lastSyncedSignature;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /** Human-readable description of the most recent error. */
    @Column(name = "last_error", length = 2000)
    private String lastError;

    /** Number of consecutive sync errors without a successful sync in between. */
    @Column(name = "consecutive_errors", nullable = false)
    private int consecutiveErrors = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IndexerStatus status = IndexerStatus.ACTIVE;

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

    public IndexerType getIndexerType() { return indexerType; }
    public void setIndexerType(IndexerType indexerType) { this.indexerType = indexerType; }

    public Long getLastSyncedBlock() { return lastSyncedBlock; }
    public void setLastSyncedBlock(Long lastSyncedBlock) { this.lastSyncedBlock = lastSyncedBlock; }

    public Long getLastFinalBlock() { return lastFinalBlock; }
    public void setLastFinalBlock(Long lastFinalBlock) { this.lastFinalBlock = lastFinalBlock; }

    public String getLastSyncedSignature() { return lastSyncedSignature; }
    public void setLastSyncedSignature(String lastSyncedSignature) { this.lastSyncedSignature = lastSyncedSignature; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public int getConsecutiveErrors() { return consecutiveErrors; }
    public void setConsecutiveErrors(int consecutiveErrors) { this.consecutiveErrors = consecutiveErrors; }

    public IndexerStatus getStatus() { return status; }
    public void setStatus(IndexerStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
