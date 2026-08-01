package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
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
 *   <li>Lag vs. best peer is ≤ {@code rpc.max-lag-blocks} (default 2)</li>
 * </ul>
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

    private final RpcNodeRepository rpcNodeRepository;
    private final Web3jClientFactory web3jClientFactory;
    private final SolanaClientFactory solanaClientFactory;
    private final BlockchainClientRegistry registry;

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
            Web3jClientFactory web3jClientFactory,
            SolanaClientFactory solanaClientFactory,
            @Nullable CantonClientProvider cantonClientFactory,
            BlockchainClientRegistry registry,
            MeterRegistry meterRegistry) {
        this.rpcNodeRepository    = rpcNodeRepository;
        this.web3jClientFactory   = web3jClientFactory;
        this.solanaClientFactory  = solanaClientFactory;
        this.cantonClientFactory  = cantonClientFactory;
        this.registry             = registry;

        // Live-queried at scrape time over the real persisted health state — checkAllNodes()
        // already saveAll()s this every ~30s, but nothing ever counted it (repo-wide
        // alerting-gap follow-up).
        Gauge.builder("registerwerk_rpc_nodes_unhealthy_total", rpcNodeRepository, RpcNodeRepository::countByHealthyFalse)
                .description("Count of RpcNode rows currently marked unhealthy")
                .register(meterRegistry);
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

        // saveAll is transactional — merges the (now detached) modified entities
        rpcNodeRepository.saveAll(allNodes);

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
        Map<UUID, Long> blockNumbers = new HashMap<>();

        for (RpcNode node : nodes) {
            if (skipProbe(node)) continue;

            Web3j client = evmHealthClients.computeIfAbsent(node.getId(),
                    id -> web3jClientFactory.createClient(node.getUrl()));

            Instant checkStart = Instant.now();
            try {
                // Check syncing status
                boolean isSyncing = client.ethSyncing()
                        .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).isSyncing();
                node.setSyncing(isSyncing);

                // Get latest block number
                BigInteger blockNum = client.ethBlockNumber()
                        .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).getBlockNumber();

                long bn = blockNum.longValue();
                blockNumbers.put(node.getId(), bn);

                if (node.getLatestBlockNumber() == null || bn > node.getLatestBlockNumber()) {
                    node.setBlockLastAdvancedAt(checkStart);
                }
                node.setLatestBlockNumber(bn);
                node.setLastSuccessAt(checkStart);
                node.setConsecutiveFailures(0);
                recordSuccess(node);

            } catch (Exception e) {
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                recordFailure(node, chain, e);
            }
            node.setLastCheckedAt(checkStart);
        }

        applyLagAndHealthEvm(nodes, blockNumbers);
    }

    private void applyLagAndHealthEvm(List<RpcNode> nodes, Map<UUID, Long> blockNumbers) {
        if (blockNumbers.isEmpty()) {
            nodes.forEach(n -> n.setHealthy(false));
            return;
        }

        long bestBlock = blockNumbers.values().stream().mapToLong(Long::longValue).max().getAsLong();

        for (RpcNode node : nodes) {
            Long nb = blockNumbers.get(node.getId());
            if (nb == null) {
                // Failed to respond — stalled if no success for a while
                boolean stalled = isStalled(node);
                if (stalled) node.setHealthy(false);
                continue;
            }

            long lag = bestBlock - nb;
            node.setLagFromBest((int) lag);

            boolean stalled = isStalled(node);
            node.setHealthy(lag <= maxLagBlocks && !node.isSyncing() && !stalled);
        }
    }

    // ── Solana ────────────────────────────────────────────────────────────────

    private void checkSolanaNodes(ChainConfig chain, List<RpcNode> nodes) {
        Map<UUID, Long> slots = new HashMap<>();

        for (RpcNode node : nodes) {
            if (skipProbe(node)) continue;

            RpcClient client = solanaHealthClients.computeIfAbsent(node.getId(),
                    id -> solanaClientFactory.createClient(node.getUrl()));

            Instant checkStart = Instant.now();
            try {
                long slot = client.getApi().getSlot();
                slots.put(node.getId(), slot);

                if (node.getLatestBlockNumber() == null || slot > node.getLatestBlockNumber()) {
                    node.setBlockLastAdvancedAt(checkStart);
                }
                node.setLatestBlockNumber(slot);
                node.setLastSuccessAt(checkStart);
                node.setConsecutiveFailures(0);
                node.setSyncing(false);
                recordSuccess(node);

            } catch (RpcException e) {
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                recordFailure(node, chain, e);
            }
            node.setLastCheckedAt(checkStart);
        }

        applySolanaLagAndHealth(nodes, slots);
    }

    private void applySolanaLagAndHealth(List<RpcNode> nodes, Map<UUID, Long> slots) {
        if (slots.isEmpty()) {
            nodes.forEach(n -> n.setHealthy(false));
            return;
        }

        long bestSlot = slots.values().stream().mapToLong(Long::longValue).max().getAsLong();

        for (RpcNode node : nodes) {
            Long ns = slots.get(node.getId());
            if (ns == null) {
                if (isStalled(node)) node.setHealthy(false);
                continue;
            }

            long lag = bestSlot - ns;
            node.setLagFromBest((int) lag);
            node.setHealthy(lag <= maxLagBlocks && !isStalled(node));
        }
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
                node.setLagFromBest(0);
                recordSuccess(node);

            } catch (Exception e) {
                node.setConsecutiveFailures(node.getConsecutiveFailures() + 1);
                node.setHealthy(false);
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
                    node.getUrl(), chain.getIdentifier(), e.getMessage());
            return;
        }
        int ticks = Math.min(1 << Math.min(failures - BACKOFF_AFTER_FAILURES, 5), MAX_BACKOFF_TICKS);
        skipTicks.put(node.getId(), ticks);
        log.warn("{} health check failed for node {} ({}) — {} consecutive failures, "
                        + "backing off for {} rounds: {}",
                chain.getChainType(), node.getUrl(), chain.getIdentifier(), failures, ticks,
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
