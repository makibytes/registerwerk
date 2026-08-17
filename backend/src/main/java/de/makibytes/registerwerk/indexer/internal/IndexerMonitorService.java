package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.indexer.events.IndexerStaleEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Scheduled monitor that periodically checks the health of all registered indexers and
 * raises warnings (and audit events) for those that are stale or in an error state.
 */
@Service
public class IndexerMonitorService {

    private static final Logger log = LoggerFactory.getLogger(IndexerMonitorService.class);

    /** An indexer is considered stale if it has not synced within this window. */
    private static final Duration STALE_THRESHOLD = Duration.ofHours(2);

    /** Each chain type's canonical indexer — used by {@link #reconcileIndexerStates()} to make
     *  sure a row exists for every enabled chain, even one that's misconfigured. Every sync
     *  service filters chains missing a required config field (graphNodeUrl/wsUrl/etc.) BEFORE
     *  ever creating an {@code IndexerState} row for it, so without this such a chain would be
     *  invisible to this monitor entirely — not flagged stale, just absent. */
    private static final Map<ChainConfig.ChainType, IndexerState.IndexerType> EXPECTED_INDEXER_TYPE = Map.of(
            ChainConfig.ChainType.EVM,      IndexerState.IndexerType.GRAPH_NODE,
            ChainConfig.ChainType.SOLANA,   IndexerState.IndexerType.SOLANA_POLL,
            ChainConfig.ChainType.STARKNET, IndexerState.IndexerType.STARKNET_POLL,
            ChainConfig.ChainType.STELLAR,  IndexerState.IndexerType.STELLAR_HORIZON,
            ChainConfig.ChainType.CANTON,   IndexerState.IndexerType.CANTON_STREAM
    );

    private final IndexerStateRepository indexerStateRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final RpcNodeRepository rpcNodeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MultiGauge lastSyncGauge;
    private final MultiGauge lagBlocksGauge;

    public IndexerMonitorService(
            IndexerStateRepository indexerStateRepository,
            ChainConfigRepository chainConfigRepository,
            RpcNodeRepository rpcNodeRepository,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.indexerStateRepository = indexerStateRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.eventPublisher = eventPublisher;
        // Exposes a timestamp (Unix epoch seconds), not a precomputed age — the idiomatic
        // Prometheus pattern is `time() - registerwerk_indexer_last_sync_timestamp_seconds`,
        // which stays accurate between this job's 5-minute updates rather than only jumping
        // once per run.
        this.lastSyncGauge = MultiGauge.builder("registerwerk_indexer_last_sync_timestamp_seconds")
                .description("Unix epoch seconds of each indexer's last successful sync; 0 if never synced")
                .register(meterRegistry);
        // docs/operator/indexers/resilience.md previously documented an
        // "indexer_lag_blocks" metric that did not exist anywhere in the code — this closes that
        // gap for real. Reuses RpcNodeHealthService's already-cached rpc_node.latest_block_number
        // (refreshed ~every 30s) as the chain-head reference rather than making a fresh RPC call
        // here, so this job stays a pure DB read.
        this.lagBlocksGauge = MultiGauge.builder("registerwerk_indexer_lag_blocks")
                .description("Blocks between the best known healthy RPC node's head and each "
                        + "indexer's last-synced block; absent when no healthy node or no synced "
                        + "block is known yet for that chain")
                .register(meterRegistry);
    }

    /** Runs once at startup so a misconfigured chain is visible immediately, rather than waiting
     *  for the next scheduled {@link #checkIndexerHealth()} pass. */
    @PostConstruct
    void reconcileOnStartup() {
        reconcileIndexerStates();
    }

    /**
     * Ensures every enabled {@link ChainConfig} has an {@code IndexerState} row for its canonical
     * indexer type, creating a placeholder {@code ERROR} row for any chain a sync service hasn't
     * created one for yet — almost always because the chain is missing a required config field.
     * One centralized reconciliation here, rather than patching each sync service's own
     * early-return logic separately.
     *
     * <p>Fetches all existing states in one query rather than one lookup per enabled chain, and
     * returns the merged (existing + newly-created) list so {@link #checkIndexerHealth()} can
     * feed it straight into {@link #refreshLastSyncGauge} without a second {@code findAll()}.
     */
    @Transactional
    List<IndexerState> reconcileIndexerStates() {
        List<IndexerState> states = new ArrayList<>(indexerStateRepository.findAll());
        Map<String, IndexerState> byChainAndType = new HashMap<>();
        for (IndexerState state : states) {
            byChainAndType.put(reconciliationKey(state.getChainConfigId(), state.getIndexerType()), state);
        }

        for (ChainConfig chain : chainConfigRepository.findByEnabledTrue()) {
            IndexerState.IndexerType expectedType = EXPECTED_INDEXER_TYPE.get(chain.getChainType());
            if (expectedType == null) {
                continue;
            }
            // Subgraph indexing is opt-in per chain: GraphNodeSyncService only polls chains with a
            // graph_node_url, and self-hosting a graph-node is a separate deployment
            // (indexer/evm/docker-compose.yml). Flagging every EVM chain without one as a broken
            // indexer would raise a permanent ERROR state for a chain nobody asked to index —
            // alert noise that trains operators to ignore this monitor.
            if (expectedType == IndexerState.IndexerType.GRAPH_NODE
                    && (chain.getGraphNodeUrl() == null || chain.getGraphNodeUrl().isBlank())) {
                continue;
            }
            if (!byChainAndType.containsKey(reconciliationKey(chain.getId(), expectedType))) {
                IndexerState state = new IndexerState();
                state.setChainConfigId(chain.getId());
                state.setIndexerType(expectedType);
                state.setStatus(IndexerState.IndexerStatus.ERROR);
                state.setLastError("No sync has ever run for this chain — check that the required "
                        + "config (rpcUrl/graphNodeUrl/wsUrl) is set for " + chain.getIdentifier() + ".");
                indexerStateRepository.save(state);
                states.add(state);
                log.warn("IndexerMonitor: chain={} type={} had no IndexerState row — created a "
                                + "placeholder ERROR state so it's no longer invisible to staleness monitoring.",
                        chain.getIdentifier(), expectedType);
            }
        }
        return states;
    }

    private static String reconciliationKey(UUID chainConfigId, IndexerState.IndexerType type) {
        return chainConfigId + ":" + type;
    }

    private void refreshLastSyncGauge(List<IndexerState> states) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (IndexerState state : states) {
            double epochSeconds = state.getLastSyncedAt() == null ? 0.0 : state.getLastSyncedAt().getEpochSecond();
            rows.add(MultiGauge.Row.of(
                    Tags.of(
                            "chain_config_id", state.getChainConfigId().toString(),
                            "indexer_type", state.getIndexerType().name()),
                    epochSeconds));
        }
        lastSyncGauge.register(rows, true);
    }

    /**
     * Blocks between each indexer's {@code last_synced_block} and the highest
     * {@code latest_block_number} reported by any enabled+healthy {@link RpcNode} on that chain.
     * Chains with no block-number-based cursor (Canton/Stellar use a signature/offset cursor
     * instead — see {@code IndexerState.lastSyncedBlock} usages) or with no healthy node yet
     * simply get no row, rather than a misleading 0.
     */
    private void refreshIndexerLagGauge(List<IndexerState> states) {
        Map<UUID, Long> bestHeadByChain = rpcNodeRepository.findAllWithChainConfig().stream()
                .filter(n -> n.isEnabled() && n.isHealthy() && n.getLatestBlockNumber() != null)
                .collect(Collectors.groupingBy(
                        n -> n.getChainConfig().getId(),
                        Collectors.mapping(RpcNode::getLatestBlockNumber, Collectors.maxBy(Comparator.naturalOrder()))))
                .entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (IndexerState state : states) {
            Long head = bestHeadByChain.get(state.getChainConfigId());
            if (head == null || state.getLastSyncedBlock() == null) {
                continue;
            }
            long lag = Math.max(0L, head - state.getLastSyncedBlock());
            rows.add(MultiGauge.Row.of(
                    Tags.of(
                            "chain_config_id", state.getChainConfigId().toString(),
                            "indexer_type", state.getIndexerType().name()),
                    (double) lag));
        }
        lagBlocksGauge.register(rows, true);
    }

    /**
     * Runs every 5 minutes. Identifies indexers that are either:
     * <ul>
     *   <li>In {@code ERROR} status, or</li>
     *   <li>Have not synced for more than {@value #STALE_THRESHOLD} hours.</li>
     * </ul>
     * Each problem indexer is logged at WARN level and triggers an {@code INDEXER_STALE} audit event.
     */
    @SchedulerLock(name = "indexerMonitor", lockAtMostFor = "PT4M")
    @Scheduled(cron = "0 */5 * * * *")
    // Not readOnly: this method also calls reconcileIndexerStates(), which inserts placeholder
    // rows. Self-invocation within the same class shares one transaction (Spring's proxy isn't
    // re-entered), so readOnly=true here would make Postgres
    // reject that INSERT as "cannot execute INSERT in a read-only transaction".
    @Transactional
    public void checkIndexerHealth() {
        try {
            List<IndexerState> states = reconcileIndexerStates();
            refreshLastSyncGauge(states);
            refreshIndexerLagGauge(states);

            Instant staleThreshold = Instant.now().minus(STALE_THRESHOLD);

            // Collect error-state indexers.
            List<IndexerState> problematic = new ArrayList<>(
                    indexerStateRepository.findByStatus(IndexerState.IndexerStatus.ERROR));

            // Collect active indexers that have not synced recently.
            List<IndexerState> stale = indexerStateRepository.findByStatusAndLastSyncedAtBefore(
                    IndexerState.IndexerStatus.ACTIVE, staleThreshold);
            problematic.addAll(stale);

            if (problematic.isEmpty()) {
                log.debug("IndexerMonitor: all indexers are healthy.");
                return;
            }

            for (IndexerState state : problematic) {
                String reason = state.getStatus() == IndexerState.IndexerStatus.ERROR
                        ? "status=ERROR, consecutiveErrors=" + state.getConsecutiveErrors()
                                + ", lastError=" + state.getLastError()
                        : "STALE — lastSyncedAt=" + state.getLastSyncedAt()
                                + " (threshold=" + staleThreshold + ")";

                log.warn("Indexer ALERT: chainConfigId={}, type={}, {}",
                        state.getChainConfigId(), state.getIndexerType(), reason);

                eventPublisher.publishEvent(new IndexerStaleEvent(state.getId(), null, null, state.getIndexerType().name(), state.getConsecutiveErrors()));
            }

            log.warn("IndexerMonitor: {} indexer(s) require attention.", problematic.size());
        } catch (Exception e) {
            log.error("Unexpected error in IndexerMonitorService.checkIndexerHealth: {}",
                    e.getMessage(), e);
        }
    }
}
