package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.CantonClientProvider;
import de.makibytes.registerwerk.chain.api.CantonLedgerEndpoint;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry mapping blockchain identifiers to their RPC clients.
 *
 * <h3>Client tiers (checked in order)</h3>
 * <ol>
 *   <li><b>Node pool</b> — populated by {@link #refreshFromNodes} (called by
 *       {@code RpcNodeHealthService} after each health-check round). Uses the
 *       multi-node selection logic described below.</li>
 *   <li><b>Dynamic single clients</b> — legacy: one client per enabled
 *       {@code chain_config} row, populated by {@link #refresh()}.</li>
 *   <li><b>Static clients</b> — loaded at startup from application properties.</li>
 * </ol>
 *
 * <h3>Node selection logic (tier 1)</h3>
 * <ol>
 *   <li>If any <em>enabled</em> node for the chain has {@code exclusive=true},
 *       restrict candidates to those exclusive-enabled nodes.</li>
 *   <li>Otherwise use all enabled nodes.</li>
 *   <li>From the candidate set prefer healthy nodes; pick the one with smallest lag.</li>
 *   <li>If no healthy candidates exist, pick the least-bad one (fewest consecutive
 *       failures, most recent success) as a last resort.</li>
 *   <li>If ALL nodes are manually disabled ({@code enabled=false}), throw —
 *       this is the only way a chain can become completely unreachable.</li>
 * </ol>
 *
 * <p>Constructed exclusively via {@code BlockchainConfig.blockchainClientRegistry()} — this
 * class must NOT also be {@code @Component}-annotated: that would create a second bean
 * definition under the same default name as the {@code @Bean} factory method, which (depending
 * on Spring's bean-definition-overriding setting) either crashes at boot or silently lets one
 * definition shadow the other.
 */
public class BlockchainClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(BlockchainClientRegistry.class);

    // ── Static clients (from BlockchainConfig / application properties) ───────

    private final Map<ChainDescriptor, Web3j>              staticEvmClients;
    private final Map<ChainDescriptor, RpcClient>          staticSolanaClients;
    private final Map<ChainDescriptor, CantonLedgerEndpoint> staticCantonClients;

    // ── Dynamic single clients (legacy, from chain_config table) ─────────────

    private volatile Map<String, Web3j>              dynamicEvmClients    = new ConcurrentHashMap<>();
    private volatile Map<String, RpcClient>          dynamicSolanaClients = new ConcurrentHashMap<>();
    private volatile Map<String, CantonLedgerEndpoint> dynamicCantonClients = new ConcurrentHashMap<>();

    // ── Node pool (from rpc_node table, populated by health checker) ──────────

    /** nodeId → Web3j client */
    private volatile Map<UUID, Web3j>              nodeEvmClients    = new ConcurrentHashMap<>();
    /** nodeId → RpcClient */
    private volatile Map<UUID, RpcClient>          nodeSolanaClients = new ConcurrentHashMap<>();
    /** nodeId → CantonLedgerEndpoint */
    private volatile Map<UUID, CantonLedgerEndpoint> nodeCantonClients = new ConcurrentHashMap<>();
    /** chainIdentifier → ordered node states for routing */
    private volatile Map<String, List<NodeState>> nodesByChain = new ConcurrentHashMap<>();
    /** nodeId → the RPC URL its cached client was built against, so URL edits invalidate it */
    private volatile Map<UUID, String>            nodeClientUrls = new ConcurrentHashMap<>();

    private ChainConfigRepository chainConfigRepository;
    private Web3jClientFactory web3jClientFactory;
    private SolanaClientFactory solanaClientFactory;
    private CantonClientProvider cantonClientFactory;

    // ── Constructor ───────────────────────────────────────────────────────────

    public BlockchainClientRegistry(
            Map<ChainDescriptor, Web3j>              evmClients,
            Map<ChainDescriptor, RpcClient>          solanaClients,
            Map<ChainDescriptor, CantonLedgerEndpoint> cantonClients,
            java.util.Optional<ChainConfigRepository> chainConfigRepository,
            java.util.Optional<Web3jClientFactory>    web3jClientFactory,
            java.util.Optional<SolanaClientFactory>   solanaClientFactory,
            java.util.Optional<CantonClientProvider>  cantonClientFactory) {
        this.staticEvmClients      = Collections.unmodifiableMap(new HashMap<>(evmClients));
        this.staticSolanaClients   = Collections.unmodifiableMap(new HashMap<>(solanaClients));
        this.staticCantonClients   = Collections.unmodifiableMap(new HashMap<>(cantonClients));
        this.chainConfigRepository = chainConfigRepository.orElse(null);
        this.web3jClientFactory    = web3jClientFactory.orElse(null);
        this.solanaClientFactory   = solanaClientFactory.orElse(null);
        this.cantonClientFactory   = cantonClientFactory.orElse(null);
    }

    // ── Lookups — identifier-based ────────────────────────────────────────────

    /**
     * Returns the best available Web3j client for the given chain identifier.
     * Uses the node pool if populated, otherwise falls back to single-client tiers.
     *
     * @throws IllegalStateException    if all nodes for the chain are manually disabled
     * @throws IllegalArgumentException if no client is registered at all
     */
    public Web3j getEvmClientByIdentifier(String identifier) {
        List<NodeState> nodes = nodesByChain.get(identifier);
        if (nodes != null && !nodes.isEmpty()) {
            return selectBestEvmNode(identifier, nodes);
        }

        // Legacy single-client fallback
        Web3j dynamic = dynamicEvmClients.get(identifier);
        if (dynamic != null) return dynamic;

        for (Map.Entry<ChainDescriptor, Web3j> entry : staticEvmClients.entrySet()) {
            String key = entry.getKey().chain().name() + "_" + entry.getKey().network().name();
            if (key.equalsIgnoreCase(identifier)) return entry.getValue();
        }
        throw new IllegalArgumentException("No EVM client configured for identifier: " + identifier);
    }

    /**
     * Returns the best available Solana RpcClient for the given chain identifier.
     */
    public RpcClient getSolanaClientByIdentifier(String identifier) {
        List<NodeState> nodes = nodesByChain.get(identifier);
        if (nodes != null && !nodes.isEmpty()) {
            return selectBestSolanaNode(identifier, nodes);
        }

        RpcClient dynamic = dynamicSolanaClients.get(identifier);
        if (dynamic != null) return dynamic;

        for (Map.Entry<ChainDescriptor, RpcClient> entry : staticSolanaClients.entrySet()) {
            String key = entry.getKey().chain().name() + "_" + entry.getKey().network().name();
            if (key.equalsIgnoreCase(identifier)) return entry.getValue();
        }
        throw new IllegalArgumentException("No Solana client configured for identifier: " + identifier);
    }

    /**
     * Returns the best available Canton client for the given chain identifier.
     */
    public CantonLedgerEndpoint getCantonClientByIdentifier(String identifier) {
        List<NodeState> nodes = nodesByChain.get(identifier);
        if (nodes != null && !nodes.isEmpty()) {
            return selectBestCantonNode(identifier, nodes);
        }

        CantonLedgerEndpoint dynamic = dynamicCantonClients.get(identifier);
        if (dynamic != null) return dynamic;

        for (Map.Entry<ChainDescriptor, CantonLedgerEndpoint> entry : staticCantonClients.entrySet()) {
            String key = entry.getKey().chain().name() + "_" + entry.getKey().network().name();
            if (key.equalsIgnoreCase(identifier)) return entry.getValue();
        }
        throw new IllegalArgumentException("No Canton client configured for identifier: " + identifier);
    }

    // ── Legacy descriptor-based lookups ──────────────────────────────────────

    public Web3j getEvmClient(ChainDescriptor descriptor) {
        Web3j client = staticEvmClients.get(descriptor);
        if (client == null) throw new IllegalArgumentException(
                "No EVM client configured for chain descriptor: " + descriptor);
        return client;
    }

    public RpcClient getSolanaClient(ChainDescriptor descriptor) {
        RpcClient client = staticSolanaClients.get(descriptor);
        if (client == null) throw new IllegalArgumentException(
                "No Solana client configured for chain descriptor: " + descriptor);
        return client;
    }

    public CantonLedgerEndpoint getCantonClient(ChainDescriptor descriptor) {
        CantonLedgerEndpoint client = staticCantonClients.get(descriptor);
        if (client == null) throw new IllegalArgumentException(
                "No Canton client configured for chain descriptor: " + descriptor);
        return client;
    }

    // ── Node pool refresh (called by health service) ──────────────────────────

    /**
     * Rebuilds the node pool from the given (freshly health-checked) node list.
     * Existing client instances are reused by node ID to preserve connection pools.
     */
    public synchronized void refreshFromNodes(List<RpcNode> nodes) {
        if (web3jClientFactory == null && solanaClientFactory == null) return;

        Map<UUID, Web3j>              newEvmClients    = new ConcurrentHashMap<>();
        Map<UUID, RpcClient>          newSolanaClients = new ConcurrentHashMap<>();
        Map<UUID, CantonLedgerEndpoint> newCantonClients = new ConcurrentHashMap<>();
        Map<String, List<NodeState>>  newByChain       = new ConcurrentHashMap<>();
        Map<UUID, String>             newClientUrls    = new ConcurrentHashMap<>();

        for (RpcNode node : nodes) {
            String identifier = node.getChainConfig().getIdentifier();
            ChainConfig chain  = node.getChainConfig();
            ChainConfig.ChainType type = chain.getChainType();

            NodeState state = new NodeState(
                    node.getId(), node.isHealthy(), node.isEnabled(), node.isExclusive(),
                    node.getConsecutiveFailures(), node.getLastSuccessAt(), node.getLagFromBest());

            // NB: reuse must be lazy. `map.getOrDefault(id, factory.createClient(url))` reads as
            // "reuse, else create", but Java evaluates arguments eagerly — it built a client for
            // every node on every refresh (this method runs every 30s) and discarded it on a cache
            // hit. Each discarded web3j client used to pin a JVM shutdown hook, so it could never
            // be collected; see Web3jClientFactory's javadoc. It also meant an edited node URL
            // never took effect, because the cached client always won.
            String url = node.getUrl();
            if (type == ChainConfig.ChainType.EVM && web3jClientFactory != null) {
                Web3j client = reusable(nodeEvmClients, node.getId(), url)
                        ? nodeEvmClients.get(node.getId())
                        : web3jClientFactory.createClient(url);
                newEvmClients.put(node.getId(), client);
                newClientUrls.put(node.getId(), url);
            } else if (type == ChainConfig.ChainType.SOLANA && solanaClientFactory != null) {
                RpcClient client = reusable(nodeSolanaClients, node.getId(), url)
                        ? nodeSolanaClients.get(node.getId())
                        : solanaClientFactory.createClient(url);
                newSolanaClients.put(node.getId(), client);
                newClientUrls.put(node.getId(), url);
            } else if (type == ChainConfig.ChainType.CANTON && cantonClientFactory != null) {
                CantonLedgerEndpoint client = reusable(nodeCantonClients, node.getId(), url)
                        ? nodeCantonClients.get(node.getId())
                        : cantonClientFactory.createClient(
                                url,
                                chain.getSynchronizerId(),
                                chain.getApplicationId() != null ? chain.getApplicationId() : "registerwerk",
                                null);
                newCantonClients.put(node.getId(), client);
                newClientUrls.put(node.getId(), url);
            }

            newByChain.computeIfAbsent(identifier, k -> new ArrayList<>()).add(state);
        }

        Map<UUID, CantonLedgerEndpoint> previousCantonClients = this.nodeCantonClients;

        this.nodeEvmClients    = newEvmClients;
        this.nodeSolanaClients = newSolanaClients;
        this.nodeCantonClients = newCantonClients;
        this.nodesByChain      = newByChain;
        this.nodeClientUrls    = newClientUrls;

        // EVM and Solana clients hold no dedicated resources (see Web3jClientFactory) and are
        // simply dropped, but a Canton endpoint owns a gRPC channel with its own event-loop
        // threads. Close any that this refresh orphaned — a removed node, or one whose URL
        // changed so a replacement client was built.
        for (Map.Entry<UUID, CantonLedgerEndpoint> previous : previousCantonClients.entrySet()) {
            if (newCantonClients.get(previous.getKey()) != previous.getValue()) {
                try {
                    previous.getValue().close();
                } catch (Exception e) {
                    log.debug("Failed to close orphaned Canton endpoint {}: {}",
                            previous.getKey(), e.getMessage());
                }
            }
        }

        log.debug("Node pool refreshed: {} chains, {} EVM nodes, {} Solana nodes, {} Canton nodes",
                newByChain.size(), newEvmClients.size(), newSolanaClients.size(), newCantonClients.size());
    }

    /**
     * True when {@code clients} already holds a client for {@code nodeId} that was built against
     * {@code url}. A URL change invalidates the cached client — otherwise editing a node's
     * endpoint would leave the registry routing to the old one indefinitely.
     */
    private boolean reusable(Map<UUID, ?> clients, UUID nodeId, String url) {
        return clients.containsKey(nodeId) && Objects.equals(nodeClientUrls.get(nodeId), url);
    }

    // ── Legacy single-client refresh (from chain_config table) ───────────────

    public void refresh() {
        if (chainConfigRepository == null) {
            log.warn("ChainConfigRepository not available; skipping dynamic client refresh.");
            return;
        }

        Map<String, Web3j>    newEvm    = new ConcurrentHashMap<>();
        Map<String, RpcClient> newSolana = new ConcurrentHashMap<>();

        Map<String, CantonLedgerEndpoint> newCanton = new ConcurrentHashMap<>();

        for (ChainConfig chain : chainConfigRepository.findByEnabledTrue()) {
            try {
                if (chain.getChainType() == ChainConfig.ChainType.EVM && web3jClientFactory != null
                        && chain.getRpcUrl() != null) {
                    newEvm.put(chain.getIdentifier(), web3jClientFactory.createClient(chain.getRpcUrl()));
                } else if (chain.getChainType() == ChainConfig.ChainType.SOLANA
                        && solanaClientFactory != null && chain.getRpcUrl() != null) {
                    newSolana.put(chain.getIdentifier(), solanaClientFactory.createClient(chain.getRpcUrl()));
                } else if (chain.getChainType() == ChainConfig.ChainType.CANTON
                        && cantonClientFactory != null && chain.getRpcUrl() != null
                        && !chain.getRpcUrl().isBlank()) {
                    String appId = chain.getApplicationId() != null ? chain.getApplicationId() : "registerwerk";
                    newCanton.put(chain.getIdentifier(), cantonClientFactory.createClient(
                            chain.getRpcUrl(), chain.getSynchronizerId(), appId, null));
                }
            } catch (Exception e) {
                log.error("Failed to refresh client for chain {}: {}", chain.getIdentifier(), e.getMessage(), e);
            }
        }

        this.dynamicEvmClients    = newEvm;
        this.dynamicSolanaClients = newSolana;
        this.dynamicCantonClients = newCanton;
        log.info("BlockchainClientRegistry refresh: {} EVM, {} Solana, {} Canton dynamic clients.",
                newEvm.size(), newSolana.size(), newCanton.size());
    }

    // ── Read-only accessors ───────────────────────────────────────────────────

    public Map<ChainDescriptor, Web3j>              getEvmClients()    { return staticEvmClients; }
    public Map<ChainDescriptor, RpcClient>          getSolanaClients() { return staticSolanaClients; }
    public Map<ChainDescriptor, CantonLedgerEndpoint> getCantonClients() { return staticCantonClients; }

    /** Returns the current node states grouped by chain identifier (for the health API). */
    public Map<String, List<NodeState>> getNodesByChain() { return Collections.unmodifiableMap(nodesByChain); }

    // ── Node selection ────────────────────────────────────────────────────────

    private Web3j selectBestEvmNode(String identifier, List<NodeState> nodes) {
        UUID id = selectBestNodeId(identifier, nodes);
        return nodeEvmClients.get(id);
    }

    private RpcClient selectBestSolanaNode(String identifier, List<NodeState> nodes) {
        UUID id = selectBestNodeId(identifier, nodes);
        return nodeSolanaClients.get(id);
    }

    private CantonLedgerEndpoint selectBestCantonNode(String identifier, List<NodeState> nodes) {
        UUID id = selectBestNodeId(identifier, nodes);
        return nodeCantonClients.get(id);
    }

    private UUID selectBestNodeId(String identifier, List<NodeState> nodes) {
        // Determine candidate set: exclusive-enabled nodes take precedence
        List<NodeState> exclusiveEnabled = nodes.stream()
                .filter(n -> n.exclusive() && n.enabled()).toList();

        List<NodeState> candidates = exclusiveEnabled.isEmpty()
                ? nodes.stream().filter(NodeState::enabled).toList()
                : exclusiveEnabled;

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "All RPC nodes for chain '" + identifier + "' are manually disabled");
        }

        // Prefer healthy with smallest lag
        Optional<NodeState> best = candidates.stream()
                .filter(NodeState::healthy)
                .min(Comparator.comparingInt(n -> n.lagFromBest() != null ? n.lagFromBest() : 0));

        if (best.isPresent()) {
            return best.get().nodeId();
        }

        // Last resort: fewest failures, most recent success
        NodeState fallback = candidates.stream()
                .min(Comparator.<NodeState>comparingInt(NodeState::consecutiveFailures)
                        .thenComparing(
                                n -> n.lastSuccessAt() != null ? n.lastSuccessAt() : Instant.EPOCH,
                                Comparator.reverseOrder()))
                .orElseThrow();

        log.warn("No healthy RPC node for chain '{}'; using fallback node {}", identifier, fallback.nodeId());
        return fallback.nodeId();
    }

    // ── NodeState record ──────────────────────────────────────────────────────

    public record NodeState(
            UUID nodeId,
            boolean healthy,
            boolean enabled,
            boolean exclusive,
            int consecutiveFailures,
            Instant lastSuccessAt,
            Integer lagFromBest) {}
}
