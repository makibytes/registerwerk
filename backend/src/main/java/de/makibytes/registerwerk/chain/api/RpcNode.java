package de.makibytes.registerwerk.chain.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single RPC endpoint for a blockchain network.
 * Multiple nodes can exist per {@link ChainConfig}. The health checker updates
 * the health fields; the operator controls {@link #enabled} and {@link #exclusive}.
 */
@Entity
@Table(name = "rpc_node")
public class RpcNode {

    /** A direct connection to a node's own RPC endpoint gives poll-based, coarse finality that
     *  can miss a short-lived reorg and has no SAFE tier without a {@code safe} block tag. A
     *  {@link #CHAINCACHE} node additionally exposes push-based, gap-free, replayable retractions
     *  and a real SAFE tier via its durable event stream — see
     *  {@code blockchain.internal.ChaincacheDurableStreamManager}. This distinction is
     *  deliberately visible in the product, not hidden behind an identical URL-in-a-table like a
     *  direct node: the whole point of chaincache as a showcase is stating, per connection, which
     *  guarantees it actually provides. */
    public enum NodeKind { DIRECT_RPC, CHAINCACHE }

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeKind kind = NodeKind.DIRECT_RPC;

    /** Base URL of the chaincache instance (e.g. {@code http://chaincache:8080}), used for the
     *  {@code GET /api/capabilities} probe and to derive the durable-stream WebSocket URL. Null
     *  for {@link NodeKind#DIRECT_RPC} nodes — {@link #url} already points at the node's own
     *  per-chain RPC endpoint for those. */
    @Column(name = "management_url", length = 512)
    private String managementUrl;

    /** Which of chaincache's own {@code chaincache.chains.<key>} multi-chain keys this
     *  {@link ChainConfig} maps to — the first path segment of every chaincache route
     *  ({@code /<key>/rpc}, {@code /<key>/ws}). Null for {@link NodeKind#DIRECT_RPC} nodes. */
    @Column(name = "remote_chain_key", length = 80)
    private String remoteChainKey;

    /** Last {@code GET /api/capabilities} response for this chain from chaincache — version,
     *  finality model, whether {@code debug_traceBlockByHash} is actually available upstream, etc.
     *  Advisory only: refreshed on add and by a periodic probe, not authoritative between probes.
     *  Null for {@link NodeKind#DIRECT_RPC} nodes and until the first successful probe. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> capabilities;

    /** Consecutive {@code redetectAll} probe failures for a {@link NodeKind#CHAINCACHE} node —
     *  see {@code RpcNodeService#redetectAll}'s demotion hysteresis. Reset to 0 on any successful
     *  probe (whether it confirms CHAINCACHE or genuinely demotes to DIRECT_RPC because chaincache
     *  no longer lists this remoteChainKey — that's a real signal, not noise). Always 0 for
     *  {@link NodeKind#DIRECT_RPC} nodes that have never been chaincache. */
    @Column(name = "chaincache_probe_failures", nullable = false)
    private int chaincacheProbeFailures = 0;

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

    public NodeKind getKind() { return kind; }
    public void setKind(NodeKind kind) { this.kind = kind; }

    public String getManagementUrl() { return managementUrl; }
    public void setManagementUrl(String managementUrl) { this.managementUrl = managementUrl; }

    public String getRemoteChainKey() { return remoteChainKey; }
    public void setRemoteChainKey(String remoteChainKey) { this.remoteChainKey = remoteChainKey; }

    public Map<String, Object> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Object> capabilities) { this.capabilities = capabilities; }

    public int getChaincacheProbeFailures() { return chaincacheProbeFailures; }
    public void setChaincacheProbeFailures(int chaincacheProbeFailures) { this.chaincacheProbeFailures = chaincacheProbeFailures; }

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
