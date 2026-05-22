package de.makibytes.registerwerk.chain.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single RPC endpoint for a blockchain network.
 * Multiple nodes can exist per {@link ChainConfig}. The health checker updates
 * the health fields; the operator controls {@link #enabled} and {@link #exclusive}.
 */
@Entity
@Table(name = "rpc_node")
public class RpcNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chain_config_id", nullable = false)
    private ChainConfig chainConfig;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(length = 100)
    private String label;

    /** False when the operator has manually stopped this node from being used. */
    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * When true, only nodes with this flag set (per chain) are eligible for use.
     * Allows the operator to pin traffic to specific nodes without disabling others.
     */
    @Column(nullable = false)
    private boolean exclusive = false;

    // ── Health fields (written by RpcNodeHealthService) ───────────────────────

    @Column(name = "latest_block_number")
    private Long latestBlockNumber;

    /** Timestamp of the last time latestBlockNumber increased — used for stall detection. */
    @Column(name = "block_last_advanced_at")
    private Instant blockLastAdvancedAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(nullable = false)
    private boolean healthy = false;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    /** How many blocks this node is behind the best-known block for its chain. Null if unknown. */
    @Column(name = "lag_from_best")
    private Integer lagFromBest;

    /** True when the node reports it is still catching up (eth_syncing). */
    @Column(nullable = false)
    private boolean syncing = false;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public ChainConfig getChainConfig() { return chainConfig; }
    public void setChainConfig(ChainConfig chainConfig) { this.chainConfig = chainConfig; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isExclusive() { return exclusive; }
    public void setExclusive(boolean exclusive) { this.exclusive = exclusive; }

    public Long getLatestBlockNumber() { return latestBlockNumber; }
    public void setLatestBlockNumber(Long latestBlockNumber) { this.latestBlockNumber = latestBlockNumber; }

    public Instant getBlockLastAdvancedAt() { return blockLastAdvancedAt; }
    public void setBlockLastAdvancedAt(Instant blockLastAdvancedAt) { this.blockLastAdvancedAt = blockLastAdvancedAt; }

    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }

    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

    public Integer getLagFromBest() { return lagFromBest; }
    public void setLagFromBest(Integer lagFromBest) { this.lagFromBest = lagFromBest; }

    public boolean isSyncing() { return syncing; }
    public void setSyncing(boolean syncing) { this.syncing = syncing; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
