package de.makibytes.registerwerk.domain.chain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Dynamic chain registry entry. Represents a concrete blockchain network that the registry
 * can interact with (index, deploy to, etc.).
 */
@Entity
@Table(name = "chain_config")
public class ChainConfig {

    public enum ChainType { EVM, SOLANA, STARKNET, STELLAR, CANTON }

    public enum NetworkType { MAINNET, TESTNET }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Stable machine-readable identifier, e.g. "ETHEREUM_MAINNET". Must be unique. */
    @Column(nullable = false, unique = true, length = 80)
    private String identifier;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "chain_type", nullable = false, length = 20)
    private ChainType chainType;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 20)
    private NetworkType networkType;

    /** EVM chain ID (e.g. 1 for Ethereum mainnet). Null for Solana. */
    @Column(name = "chain_id")
    private Long chainId;

    @Column(name = "rpc_url", nullable = false, length = 512)
    private String rpcUrl;

    @Column(name = "ws_url", length = 512)
    private String wsUrl;

    /**
     * Comma-separated list of fallback RPC URLs. Use {@link #getFallbackRpcUrlList()} for
     * a parsed {@link List}.
     */
    @Column(name = "fallback_rpc_urls", columnDefinition = "text")
    private String fallbackRpcUrls;

    /** Block explorer base URL without a trailing slash, e.g. "https://etherscan.io". */
    @Column(name = "block_explorer_url", length = 512)
    private String blockExplorerUrl;

    /** Base URL of the Graph Node indexer, e.g. "https://graph.example.com". */
    @Column(name = "graph_node_url", length = 512)
    private String graphNodeUrl;

    /** Subgraph name deployed on the Graph Node. */
    @Column(name = "graph_subgraph_name", length = 200)
    private String graphSubgraphName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns fallback RPC URLs as a parsed list. Never null.
     */
    public List<String> getFallbackRpcUrlList() {
        if (fallbackRpcUrls == null || fallbackRpcUrls.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(fallbackRpcUrls.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Sets fallback RPC URLs from a list by joining with commas.
     */
    public void setFallbackRpcUrlList(List<String> urls) {
        this.fallbackRpcUrls = (urls == null || urls.isEmpty()) ? null : String.join(",", urls);
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }

    public NetworkType getNetworkType() { return networkType; }
    public void setNetworkType(NetworkType networkType) { this.networkType = networkType; }

    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }

    public String getRpcUrl() { return rpcUrl; }
    public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }

    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }

    public String getFallbackRpcUrls() { return fallbackRpcUrls; }
    public void setFallbackRpcUrls(String fallbackRpcUrls) { this.fallbackRpcUrls = fallbackRpcUrls; }

    public String getBlockExplorerUrl() { return blockExplorerUrl; }
    public void setBlockExplorerUrl(String blockExplorerUrl) { this.blockExplorerUrl = blockExplorerUrl; }

    public String getGraphNodeUrl() { return graphNodeUrl; }
    public void setGraphNodeUrl(String graphNodeUrl) { this.graphNodeUrl = graphNodeUrl; }

    public String getGraphSubgraphName() { return graphSubgraphName; }
    public void setGraphSubgraphName(String graphSubgraphName) { this.graphSubgraphName = graphSubgraphName; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
