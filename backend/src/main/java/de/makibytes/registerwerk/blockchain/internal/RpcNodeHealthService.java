package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcUrls;
import de.makibytes.registerwerk.chain.api.RpcNodeChainVerifier;
import de.makibytes.registerwerk.chain.api.CantonClientProvider;
import de.makibytes.registerwerk.chain.api.CantonLedgerEndpoint;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;

import org.springframework.lang.Nullable;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Periodically checks the health of every registered RPC node and updates
 * the {@link RpcNode} health fields in the database. After each round it
 * triggers a refresh of the {@link BlockchainClientRegistry} so routing
 * immediately reflects the updated health state.
 *
 * <p>Health criteria (must ALL be true to be healthy):
 * <ul>
 *   <li>Node responds within {@code rpc.health-check-timeout-seconds} (default 5s)</li>
 *   <li>Not reporting syncing mode (EVM: eth_syncing)</li>
 *   <li>Block number has advanced within {@code rpc.stall-threshold-seconds} (default 120s)</li>
 *   <li>Lag vs. the reference height is ≤ {@code rpc.max-lag-blocks} (default 2). The reference is
 *       the <em>median</em> height of the routing candidates (lower median, {@code >= 3} candidates) or
 *       the lower of two, never the maximum: one lying node cannot make every honest node look late.
 *       A node ahead of the reference by more than {@code rpc.max-plausible-jump-blocks} is
 *       quarantined (IMPLAUSIBLE_HEIGHT).</li>
 *   <li>The node serves the pinned chain: {@code eth_chainId} equals {@code chain_config.chain_id} and
 *       the genesis hash equals {@code chain_config.genesis_hash} (Solana: genesis hash). A mismatch
 *       marks the node unhealthy with reason CHAIN_MISMATCH and it is never routed to (P4C-1).</li>
 * </ul>
 * A single failed probe makes a node unhealthy immediately; coming back needs two consecutive good
 * probes (P4B-8).
 */
@Service
public class RpcNodeHealthService {

    private static final Logger log = LoggerFactory.getLogger(RpcNodeHealthService.class);

    @Value("${registerwerk.rpc.health-check-timeout-seconds:5}")
    private long timeoutSeconds;

    @Value("${registerwerk.rpc.stall-threshold-seconds:120}")
    private long stallThresholdSeconds;

    @Value("${registerwerk.rpc.max-lag-blocks:2}")
    private int maxLagBlocks;

    /** A node reporting a height this far above the reference (median / lower peer) is not believed. */
    @Value("${registerwerk.rpc.max-plausible-jump-blocks:1000}")
    private long maxPlausibleJump;

    /** Consecutive good probes an unhealthy node needs before it is routed to again. */
    private static final int RECOVERY_SUCCESSES = 2;

    private final RpcNodeRepository rpcNodeRepository;
    private final RpcNodeHealthPersister healthPersister;
    private final Web3jClientFactory web3jClientFactory;
    private final SolanaClientFactory solanaClientFactory;
    private final BlockchainClientRegistry registry;
    private final RpcChainIdentityChecker identityChecker;

    private final CantonClientProvider cantonClientFactory;

    /**
     * Consecutive failures past which a node is only re-probed on an exponentially growing
     * interval. The default seed data ships placeholder endpoints (`.../v3/changeme`) and chains
     * with no public RPC, so without this a stock demo deployment probes ~10 endpoints that can
     * never answer, every tick, forever — burning the timeout budget and emitting a WARN each time.
     */
    private static final int BACKOFF_AFTER_FAILURES = 3;
    /** Cap so a node that recovers is picked up again within a bounded time. */
    private static final int MAX_BACKOFF_TICKS = 32;

    /** Cached clients keyed by node ID to avoid creating new connections on every tick. */
    private final Map<UUID, Web3j>              evmHealthClients    = new ConcurrentHashMap<>();
    private final Map<UUID, RpcClient>          solanaHealthClients = new ConcurrentHashMap<>();
    private final Map<UUID, CantonLedgerEndpoint> cantonHealthClients = new ConcurrentHashMap<>();

    /** nodeId → ticks still to skip before the next probe (backoff for persistently dead nodes). */
    private final Map<UUID, Integer> skipTicks = new ConcurrentHashMap<>();

    public RpcNodeHealthService(
            RpcNodeRepository rpcNodeRepository,
            RpcNodeHealthPersister healthPersister,
            Web3jClientFactory web3jClientFactory,
            SolanaClientFactory solanaClientFactory,
            @Nullable CantonClientProvider cantonClientFactory,
            BlockchainClientRegistry registry,
            RpcChainIdentityChecker identityChecker,
            MeterRegistry meterRegistry) {
        this.rpcNodeRepository    = rpcNodeRepository;
        this.healthPersister      = healthPersister;
        this.web3jClientFactory   = web3jClientFactory;
        this.solanaClientFactory  = solanaClientFactory;
        this.cantonClientFactory  = cantonClientFactory;
        this.registry             = registry;
        this.identityChecker      = identityChecker;

        // Live-queried at scrape time over the real persisted health state — checkAllNodes()
        // already saveAll()s this every ~30s, but nothing ever counted it (repo-wide
        // alerting gauge).
        Gauge.builder("registerwerk_rpc_nodes_unhealthy_total", rpcNodeRepository, RpcNodeRepository::countByHealthyFalse)
                .description("Count of RpcNode rows currently marked unhealthy")
                .register(meterRegistry);

        // One series per RpcNode.NodeKind — how much of the fleet is actually routed through
        // chaincache vs. direct nodes, the headline number for the whole showcase.
        for (RpcNode.NodeKind kind : RpcNode.NodeKind.values()) {
            Gauge.builder("registerwerk_chaincache_nodes_total", rpcNodeRepository, repo -> repo.countByKind(kind))
                    .description("Count of RpcNode rows by kind (DIRECT_RPC vs CHAINCACHE)")
                    .tag("kind", kind.name())
                    .register(meterRegistry);
        }
    }

    // Deliberately NOT @SchedulerLock'd: the tail of this method calls
    // registry.refreshFromNodes(allNodes) to refresh THIS instance's in-memory
    // BlockchainClientRegistry, which every instance needs independently to route its
    // own outbound RPC calls away from unhealthy nodes. Serializing this job across
    // instances would leave all-but-one instance routing through stale/dead RPC nodes.
    // The RpcNode row writes are idempotent last-writer-wins health metrics, safe to race.
    @Scheduled(fixedDelayString = "${registerwerk.rpc.health-check-interval-ms:30000}",
               initialDelayString = "${registerwerk.rpc.health-check-initial-delay-ms:10000}")
    public void checkAll() {
        // findAllWithChainConfig is transactional — returns detached entities with chainConfig eager-loaded.
        // No DB transaction is held during the HTTP health checks below.
        List<RpcNode> allNodes = rpcNodeRepository.findAllWithChainConfig();
        if (allNodes.isEmpty()) return;

        Map<UUID, List<RpcNode>> byChain = allNodes.stream()
                .collect(Collectors.groupingBy(n -> n.getChainConfig().getId()));

        for (List<RpcNode> nodes : byChain.values()) {
            ChainConfig chain = nodes.get(0).getChainConfig();
            try {
                if (chain.getChainType() == ChainConfig.ChainType.EVM) {
                    checkEvmNodes(chain, nodes);
                } else if (chain.getChainType() == ChainConfig.ChainType.SOLANA) {
                    checkSolanaNodes(chain, nodes);
                } else if (chain.getChainType() == ChainConfig.ChainType.CANTON && cantonClientFactory != null) {
                    checkCantonNodes(chain, nodes);
                }
            } catch (Exception e) {
                log.error("Health check failed for chain {}: {}", chain.getIdentifier(), e.getMessage(), e);
            }
        }

        // Targeted per-node column update instead of saveAll(allNodes): saveAll() persists every
        // field of these detached entities, including kind/managementUrl/remoteChainKey/capabilities
        // as they stood at the top of this method (seconds ago) — clobbering any chaincache
        // detection write (or admin edit) that landed concurrently. This writer only ever owns the
        // health-check columns; see RpcNodeRepository.updateHealthFields's javadoc. Delegated to a
        // separate @Transactional bean — updateHealthFields flushes automatically and needs a live
        // transaction, and checkAll() itself must stay non-transactional (see
        // RpcNodeHealthPersister's javadoc for why this can't just be an annotation on this method).
        healthPersister.persist(allNodes);

        // Purge stale health-check clients for nodes that were removed. EVM and Solana clients
        // hold no dedicated resources (see Web3jClientFactory) and are simply dropped; Canton
        // endpoints own a gRPC channel and must be closed.
        Set<UUID> activeIds = allNodes.stream().map(RpcNode::getId).collect(Collectors.toSet());
        evmHealthClients.keySet().removeIf(id -> !activeIds.contains(id));
        solanaHealthClients.keySet().removeIf(id -> !activeIds.contains(id));
        skipTicks.keySet().removeIf(id -> !activeIds.contains(id));
        for (UUID staleId : Set.copyOf(cantonHealthClients.keySet())) {
            if (!activeIds.contains(staleId)) closeQuietly(cantonHealthClients.remove(staleId));
        }

        registry.refreshFromNodes(allNodes);
    }

    // ── EVM ───────────────────────────────────────────────────────────────────

    private void checkEvmNodes(ChainConfig chain, List<RpcNode> nodes) {
        Probes probes = new Probes();

        for (RpcNode node : nodes) {
            if (skipProbe(node)) continue;

            Web3j client = evmHealthClients.computeIfAbsent(node.getId(),
                    id -> web3jClientFactory.createClient(node.getUrl()));

            Instant checkStart = Instant.now();
            if (node.getLastCheckedAt() == null) probes.firstProbe.add(node.getId());
            try {
                // Check syncing status
                boolean isSyncing = client.ethSyncing()
                        .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).isSyncing();
                node.setSyncing(isSyncing);

                // Get latest block number
                BigInteger blockNum = client.ethBlockNumber()
                        .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).getBlockNumber();

                long bn = blockNum.longValue();

                // P4C-1: the endpoint must serve the pinned chain (chain id + genesis hash).
                RpcNodeChainVerifier.Verdict identity = identityChecker.checkEvm(chain, client);
                if (identity.outcome() == RpcNodeChainVerifier.Outcome.MISMATCH) {
                    log.error("RPC node {} of chain {} serves another chain ({}); quarantined",
                            RpcUrls.redact(node.getUrl()), chain.getIdentifier(), identity.detail());
                    probes.mismatched.add(node.getId());
                    node.setConsecutiveFailures(0);
                    node.setLastSuccessAt(checkStart);
                } else {
                    probes.reported.put(node.getId(), bn);
                    if (node.getLatestBlockNumber() == null || bn > node.getLatestBlockNumber()) {
                        node.setBlockLastAdvancedAt(checkStart);
                    }
                    node.setLatestBlockNumber(bn);
                    node.setLastSuccessAt(checkStart);
                    node.setConsecutiveFailures(0);
                }
                recordSuccess(node);

            } catch (Exception e) {
                probes.failed.add(node.getId());
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                recordFailure(node, chain, e);
            }
            node.setLastCheckedAt(checkStart);
        }

        applyLagAndHealth(nodes, probes, true);
    }

    /** Outcome of one probe round for a chain. */
    static final class Probes {
        /** nodeId → reported height/slot, only for nodes that answered and serve the pinned chain. */
        final Map<UUID, Long> reported = new HashMap<>();
        final Set<UUID> mismatched = new HashSet<>();
        final Set<UUID> failed = new HashSet<>();
        /** Nodes probed for the first time ever: no hysteresis, they have no unhealthy history. */
        final Set<UUID> firstProbe = new HashSet<>();
    }

    /**
     * Applies health to every node of a chain from one probe round (EVM and Solana share the rules):
     * a failed probe is unhealthy at once; a chain mismatch quarantines; lag is measured against the
     * median/lower-peer reference (never the maximum); a node far ahead of it is quarantined; a node
     * that was unhealthy needs {@link #RECOVERY_SUCCESSES} consecutive good probes to be healthy again.
     */
    /** The implausible-height quarantine needs at least this many comparable reliable nodes. */
    static final int MIN_NODES_FOR_JUMP_QUARANTINE = 3;

    void applyLagAndHealth(List<RpcNode> nodes, Probes probes, boolean checkSyncing) {
        for (RpcNode node : nodes) {
            UUID id = node.getId();
            if (probes.mismatched.contains(id)) {
                markUnhealthy(node, RpcNode.HealthReason.CHAIN_MISMATCH);
            } else if (probes.failed.contains(id)) {
                markUnhealthy(node, RpcNode.HealthReason.PROBE_FAILED);
            }
        }
        if (probes.reported.isEmpty()) {
            for (RpcNode node : nodes) {
                if (!probes.mismatched.contains(node.getId()) && !probes.failed.contains(node.getId())) {
                    markUnhealthy(node, RpcNode.HealthReason.PROBE_FAILED);
                }
            }
            return;
        }

        Set<UUID> reliable = reliableIds(nodes, probes.reported, checkSyncing);
        long reference = referenceHeight(nodes, probes.reported, reliable);
        int comparable = comparableCount(nodes, probes.reported, reliable);
        List<RpcNode> implausible = new ArrayList<>();

        for (RpcNode node : nodes) {
            Long nb = probes.reported.get(node.getId());
            if (nb == null) continue; // failed / mismatched (handled) or skipped by backoff (unchanged)

            long lag = Math.max(0, reference - nb);
            node.setLagFromBest((int) Math.min(lag, Integer.MAX_VALUE));

            // A "too far ahead" verdict needs a real quorum: with 1-2 nodes the reference is a single
            // peer, and a stalled/syncing peer would otherwise get the HONEST node quarantined (total
            // outage). The reference itself is built from healthy, non-syncing, non-stalled nodes only.
            if (comparable >= MIN_NODES_FOR_JUMP_QUARANTINE && nb - reference > maxPlausibleJump) {
                log.error("RPC node {} reports height {} but the reference is {}; quarantined as implausible",
                        RpcUrls.redact(node.getUrl()), nb, reference);
                markUnhealthy(node, RpcNode.HealthReason.IMPLAUSIBLE_HEIGHT);
                implausible.add(node);
                continue;
            }
            String problem = null;
            if (checkSyncing && node.isSyncing()) problem = RpcNode.HealthReason.SYNCING;
            else if (lag > maxLagBlocks) problem = RpcNode.HealthReason.LAGGING;
            else if (isStalled(node)) problem = RpcNode.HealthReason.STALLED;

            if (problem != null) {
                markUnhealthy(node, problem);
                continue;
            }
            int successes = node.getConsecutiveSuccesses() + 1;
            node.setConsecutiveSuccesses(successes);
            if (node.isHealthy() || successes >= RECOVERY_SUCCESSES || probes.firstProbe.contains(node.getId())) {
                node.setHealthy(true);
                node.setHealthReason(null);
            } else {
                node.setHealthy(false);
                node.setHealthReason(RpcNode.HealthReason.RECOVERING);
            }
        }

        // Never leave zero routable nodes because of IMPLAUSIBLE_HEIGHT: if the quarantine emptied the
        // healthy set, keep the node(s) in service (degrade to ERROR log + the unhealthy/alert metrics).
        boolean anyHealthy = nodes.stream().anyMatch(n -> n.isEnabled() && n.isHealthy());
        if (!implausible.isEmpty() && !anyHealthy) {
            for (RpcNode node : implausible) {
                log.error("RPC node {} would be the last routable node; keeping it in service despite an "
                        + "implausible height (operator review needed)", RpcUrls.redact(node.getUrl()));
                node.setHealthy(true);
                node.setHealthReason(null);
            }
        }
    }

    private static void markUnhealthy(RpcNode node, String reason) {
        node.setHealthy(false);
        node.setHealthReason(reason);
        node.setConsecutiveSuccesses(0);
    }

    // ── Solana ────────────────────────────────────────────────────────────────

    private void checkSolanaNodes(ChainConfig chain, List<RpcNode> nodes) {
        Probes probes = new Probes();

        for (RpcNode node : nodes) {
            if (skipProbe(node)) continue;

            RpcClient client = solanaHealthClients.computeIfAbsent(node.getId(),
                    id -> solanaClientFactory.createClient(node.getUrl()));

            Instant checkStart = Instant.now();
            if (node.getLastCheckedAt() == null) probes.firstProbe.add(node.getId());
            try {
                long slot = client.getApi().getSlot();

                // P4C-1: Solana has no chain id; the genesis hash identifies the cluster.
                RpcNodeChainVerifier.Verdict identity = identityChecker.checkSolana(chain, client);
                if (identity.outcome() == RpcNodeChainVerifier.Outcome.MISMATCH) {
                    log.error("Solana RPC node {} of chain {} serves another cluster ({}); quarantined",
                            RpcUrls.redact(node.getUrl()), chain.getIdentifier(), identity.detail());
                    probes.mismatched.add(node.getId());
                    node.setConsecutiveFailures(0);
                    node.setLastSuccessAt(checkStart);
                } else {
                    probes.reported.put(node.getId(), slot);
                    if (node.getLatestBlockNumber() == null || slot > node.getLatestBlockNumber()) {
                        node.setBlockLastAdvancedAt(checkStart);
                    }
                    node.setLatestBlockNumber(slot);
                    node.setLastSuccessAt(checkStart);
                    node.setConsecutiveFailures(0);
                    node.setSyncing(false);
                }
                recordSuccess(node);

            } catch (RpcException e) {
                probes.failed.add(node.getId());
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                recordFailure(node, chain, e);
            }
            node.setLastCheckedAt(checkStart);
        }

        applyLagAndHealth(nodes, probes, false);
    }

    // ── Canton ────────────────────────────────────────────────────────────────

    private void checkCantonNodes(ChainConfig chain, List<RpcNode> nodes) {
        for (RpcNode node : nodes) {
            if (skipProbe(node)) continue;

            Instant checkStart = Instant.now();
            try {
                CantonLedgerEndpoint client = cantonHealthClients.computeIfAbsent(node.getId(),
                        id -> cantonClientFactory.createClient(
                                node.getUrl(),
                                chain.getSynchronizerId(),
                                chain.getApplicationId() != null ? chain.getApplicationId() : "registerwerk",
                                null));

                // Ping via ledger-end — lightweight, no streaming
                client.getLedgerEnd();

                node.setLastSuccessAt(checkStart);
                node.setConsecutiveFailures(0);
                node.setSyncing(false);
                node.setHealthy(true);
                node.setHealthReason(null);
                node.setLagFromBest(0);
                recordSuccess(node);

            } catch (Exception e) {
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                node.setHealthy(false);
                node.setHealthReason(RpcNode.HealthReason.PROBE_FAILED);
                recordFailure(node, chain, e);
                // Unlike the EVM/Solana clients, a Canton endpoint owns a gRPC channel with its
                // own event-loop threads, so it must be closed rather than simply dropped.
                closeQuietly(cantonHealthClients.remove(node.getId()));
            }
            node.setLastCheckedAt(checkStart);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The reference height lag is measured against: the <em>median</em> of the heights reported by the
     * routing candidates (exclusive-and-enabled nodes if any exist, else every enabled node - the same
     * set {@code BlockchainClientRegistry.selectBestNodeId} routes among, so a real public node sitting
     * on the same {@code ChainConfig} as a local devnet, or a node an operator disabled, cannot skew
     * it). With three or more reports this is the lower median; with two it is the lower of the two;
     * with one it is that value. Using the median instead of the maximum means a single lying node
     * cannot make every honest node look late (P4C-1). Falls back to every node that reported this
     * tick if the candidate set itself reported nothing.
     */
    static long referenceHeight(List<RpcNode> nodes, Map<UUID, Long> reported, Set<UUID> reliable) {
        List<Long> values = candidateValues(nodes, reported, reliable);
        java.util.Collections.sort(values);
        return values.get((values.size() - 1) / 2);
    }

    /** Nodes counted for the jump quarantine: only reliable candidates (0 when the fallback set is used). */
    private static int comparableCount(List<RpcNode> nodes, Map<UUID, Long> reported, Set<UUID> reliable) {
        Set<UUID> candidateIds = routingCandidateIds(nodes);
        return (int) reported.keySet().stream()
                .filter(id -> candidateIds.contains(id) && reliable.contains(id)).count();
    }

    private static List<Long> candidateValues(List<RpcNode> nodes, Map<UUID, Long> reported, Set<UUID> reliable) {
        Set<UUID> candidateIds = routingCandidateIds(nodes);
        List<Long> values = reported.entrySet().stream()
                .filter(e -> candidateIds.contains(e.getKey()) && reliable.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toCollection(ArrayList::new));
        if (values.isEmpty()) {
            values = reported.entrySet().stream()
                    .filter(e -> candidateIds.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (values.isEmpty()) values = new ArrayList<>(reported.values());
        return values;
    }

    /** Reporting nodes that may serve as reference: not syncing (EVM) and not stalled. Their previous
     *  LAGGING/STALLED verdict is not used, so a node can always recover. */
    private Set<UUID> reliableIds(List<RpcNode> nodes, Map<UUID, Long> reported, boolean checkSyncing) {
        return nodes.stream()
                .filter(n -> reported.containsKey(n.getId()))
                .filter(n -> !(checkSyncing && n.isSyncing()))
                .filter(n -> !isStalled(n))
                .map(RpcNode::getId)
                .collect(Collectors.toSet());
    }

    /** Mirrors {@code BlockchainClientRegistry.selectBestNodeId}'s candidate-set derivation:
     *  exclusive-and-enabled nodes if any exist, else every enabled node. Duplicated rather than
     *  shared — {@code blockchain.api} and {@code blockchain.internal} are different Modulith
     *  layers, and this is a three-line pure function, not worth a new cross-module port for. */
    private static Set<UUID> routingCandidateIds(List<RpcNode> nodes) {
        List<RpcNode> exclusiveEnabled = nodes.stream().filter(n -> n.isExclusive() && n.isEnabled()).toList();
        List<RpcNode> candidates = exclusiveEnabled.isEmpty()
                ? nodes.stream().filter(RpcNode::isEnabled).toList()
                : exclusiveEnabled;
        return candidates.stream().map(RpcNode::getId).collect(Collectors.toSet());
    }

    /**
     * Whether to skip probing this node on this round — either because it (or its chain) is
     * disabled, or because it is serving out an exponential backoff after repeated failures.
     *
     * <p>Disabled nodes are still handed to {@code refreshFromNodes} by the caller, so routing
     * semantics are unchanged; they are merely not probed over the network.
     */
    private boolean skipProbe(RpcNode node) {
        if (!node.isEnabled() || !node.getChainConfig().isEnabled()) return true;

        Integer remaining = skipTicks.get(node.getId());
        if (remaining == null || remaining <= 0) return false;
        skipTicks.put(node.getId(), remaining - 1);
        return true;
    }

    /** Clears any backoff so a recovered node returns to the normal cadence immediately. */
    private void recordSuccess(RpcNode node) {
        skipTicks.remove(node.getId());
    }

    /**
     * Applies exponential backoff and log throttling after a failed probe. The first
     * {@link #BACKOFF_AFTER_FAILURES} failures are logged and re-probed normally, so genuine
     * transient outages stay visible; beyond that a node is probed on a doubling interval and
     * logged only on the rounds it is actually probed.
     */
    private void recordFailure(RpcNode node, ChainConfig chain, Exception e) {
        int failures = node.getConsecutiveFailures();
        if (failures <= BACKOFF_AFTER_FAILURES) {
            log.warn("{} health check failed for node {} ({}): {}", chain.getChainType(),
                    RpcUrls.redact(node.getUrl()), chain.getIdentifier(), e.getMessage());
            return;
        }
        int ticks = Math.min(1 << Math.min(failures - BACKOFF_AFTER_FAILURES, 5), MAX_BACKOFF_TICKS);
        skipTicks.put(node.getId(), ticks);
        log.warn("{} health check failed for node {} ({}) — {} consecutive failures, "
                        + "backing off for {} rounds: {}",
                chain.getChainType(), RpcUrls.redact(node.getUrl()), chain.getIdentifier(), failures, ticks,
                e.getMessage());
    }

    private void closeQuietly(@Nullable AutoCloseable client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Failed to close RPC client: {}", e.getMessage());
        }
    }

    private boolean isStalled(RpcNode node) {
        Instant lastAdvanced = node.getBlockLastAdvancedAt();
        if (lastAdvanced == null) {
            // Never successfully seen a block — stalled if we've been checking for a while
            Instant lastCheck = node.getLastCheckedAt();
            return lastCheck != null &&
                    Duration.between(lastCheck, Instant.now()).toSeconds() > stallThresholdSeconds;
        }
        return Duration.between(lastAdvanced, Instant.now()).toSeconds() > stallThresholdSeconds;
    }
}
