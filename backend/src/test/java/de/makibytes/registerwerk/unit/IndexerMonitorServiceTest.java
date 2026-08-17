package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.internal.IndexerMonitorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the centralized reconciliation fix : previously a chain
 * missing required config (graphNodeUrl/wsUrl/etc.) never got an {@code IndexerState} row created
 * at all, since every sync service filtered it out before ever reaching that creation step —
 * making it invisible to this monitor entirely rather than flagged stale.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndexerMonitorService — reconciles missing IndexerState rows")
class IndexerMonitorServiceTest {

    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private RpcNodeRepository rpcNodeRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private IndexerMonitorService service;
    private SimpleMeterRegistry meterRegistry;

    private ChainConfig chain(ChainConfig.ChainType type) {
        ChainConfig c = new ChainConfig();
        c.setId(UUID.randomUUID());
        c.setIdentifier(type.name() + "_TESTNET");
        c.setChainType(type);
        c.setEnabled(true);
        // EVM chains are only expected to have a graph-node indexer when one is configured —
        // subgraph indexing is opt-in per chain. Set it here so the general-purpose fixture
        // represents a chain that IS meant to be indexed.
        if (type == ChainConfig.ChainType.EVM) {
            c.setGraphNodeUrl("http://graph-node:8000/subgraphs/name");
        }
        return c;
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IndexerMonitorService(indexerStateRepository, chainConfigRepository, rpcNodeRepository, eventPublisher, meterRegistry);
    }

    private void invokeReconcile() {
        ReflectionTestUtils.invokeMethod(service, "reconcileIndexerStates");
    }

    @Test
    @DisplayName("creates a placeholder ERROR row for an enabled chain with no existing IndexerState")
    void reconcile_createsPlaceholder_whenMissing() {
        ChainConfig evmChain = chain(ChainConfig.ChainType.EVM);
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of(evmChain));
        when(indexerStateRepository.findAll()).thenReturn(List.of());

        invokeReconcile();

        ArgumentCaptor<IndexerState> captor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isEqualTo(evmChain.getId());
        assertThat(captor.getValue().getIndexerType()).isEqualTo(IndexerState.IndexerType.GRAPH_NODE);
        assertThat(captor.getValue().getStatus()).isEqualTo(IndexerState.IndexerStatus.ERROR);
        assertThat(captor.getValue().getLastError()).contains(evmChain.getIdentifier());
    }

    @Test
    @DisplayName("does not flag an EVM chain that has no graph-node configured")
    void reconcile_skipsEvmChainWithoutGraphNodeUrl() {
        // Subgraph indexing is opt-in: GraphNodeSyncService only polls chains with a
        // graph_node_url, and self-hosting graph-node is a separate deployment. Raising a
        // permanent ERROR state for a chain nobody asked to index is pure alert noise.
        ChainConfig evmChain = chain(ChainConfig.ChainType.EVM);
        evmChain.setGraphNodeUrl(null);
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of(evmChain));
        when(indexerStateRepository.findAll()).thenReturn(List.of());

        invokeReconcile();

        verify(indexerStateRepository, never()).save(any(IndexerState.class));
    }

    @Test
    @DisplayName("still flags a non-EVM chain, which needs no graph-node configuration")
    void reconcile_stillFlagsNonEvmChain() {
        ChainConfig solanaChain = chain(ChainConfig.ChainType.SOLANA);
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of(solanaChain));
        when(indexerStateRepository.findAll()).thenReturn(List.of());

        invokeReconcile();

        ArgumentCaptor<IndexerState> captor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(captor.capture());
        assertThat(captor.getValue().getIndexerType()).isEqualTo(IndexerState.IndexerType.SOLANA_POLL);
    }

    @Test
    @DisplayName("does not touch a chain that already has an IndexerState row")
    void reconcile_doesNothing_whenAlreadyExists() {
        ChainConfig solanaChain = chain(ChainConfig.ChainType.SOLANA);
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of(solanaChain));

        IndexerState existing = new IndexerState();
        existing.setChainConfigId(solanaChain.getId());
        existing.setIndexerType(IndexerState.IndexerType.SOLANA_POLL);
        when(indexerStateRepository.findAll()).thenReturn(List.of(existing));

        invokeReconcile();

        verify(indexerStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("maps each chain type to its canonical indexer type")
    void reconcile_mapsEachChainTypeCorrectly() {
        ChainConfig starknetChain = chain(ChainConfig.ChainType.STARKNET);
        ChainConfig stellarChain = chain(ChainConfig.ChainType.STELLAR);
        ChainConfig cantonChain = chain(ChainConfig.ChainType.CANTON);
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of(starknetChain, stellarChain, cantonChain));
        when(indexerStateRepository.findAll()).thenReturn(List.of());

        invokeReconcile();

        ArgumentCaptor<IndexerState> captor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(IndexerState::getChainConfigId, IndexerState::getIndexerType)
                .containsExactlyInAnyOrder(
                        tuple(starknetChain.getId(), IndexerState.IndexerType.STARKNET_POLL),
                        tuple(stellarChain.getId(), IndexerState.IndexerType.STELLAR_HORIZON),
                        tuple(cantonChain.getId(), IndexerState.IndexerType.CANTON_STREAM));
    }

    @Test
    @DisplayName("checkIndexerHealth() publishes a last-sync-timestamp gauge per indexer ")
    void checkIndexerHealth_publishesLastSyncGauge() {
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of());

        IndexerState synced = new IndexerState();
        synced.setChainConfigId(UUID.randomUUID());
        synced.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
        synced.setStatus(IndexerState.IndexerStatus.ACTIVE);
        Instant syncedAt = Instant.now().minusSeconds(120);
        synced.setLastSyncedAt(syncedAt);

        IndexerState neverSynced = new IndexerState();
        neverSynced.setChainConfigId(UUID.randomUUID());
        neverSynced.setIndexerType(IndexerState.IndexerType.SOLANA_POLL);
        neverSynced.setStatus(IndexerState.IndexerStatus.ERROR);

        when(indexerStateRepository.findAll()).thenReturn(List.of(synced, neverSynced));
        when(indexerStateRepository.findByStatus(IndexerState.IndexerStatus.ERROR)).thenReturn(List.of(neverSynced));
        when(indexerStateRepository.findByStatusAndLastSyncedAtBefore(any(), any())).thenReturn(List.of());

        service.checkIndexerHealth();

        double syncedGaugeValue = meterRegistry.get("registerwerk_indexer_last_sync_timestamp_seconds")
                .tag("indexer_type", "GRAPH_NODE")
                .gauge()
                .value();
        assertThat(syncedGaugeValue).isEqualTo((double) syncedAt.getEpochSecond());

        double neverSyncedGaugeValue = meterRegistry.get("registerwerk_indexer_last_sync_timestamp_seconds")
                .tag("indexer_type", "SOLANA_POLL")
                .gauge()
                .value();
        assertThat(neverSyncedGaugeValue).isEqualTo(0.0);
    }

    @Test
    @DisplayName("checkIndexerHealth() publishes an indexer-lag-blocks gauge from the best healthy "
            + "RPC node's head, and omits chains with no healthy node or no synced block yet")
    void checkIndexerHealth_publishesLagBlocksGauge() {
        when(chainConfigRepository.findByEnabledTrue()).thenReturn(List.of());

        UUID laggingChainId = UUID.randomUUID();
        IndexerState lagging = new IndexerState();
        lagging.setChainConfigId(laggingChainId);
        lagging.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
        lagging.setStatus(IndexerState.IndexerStatus.ACTIVE);
        lagging.setLastSyncedAt(Instant.now());
        lagging.setLastSyncedBlock(90L);

        UUID noHealthyNodeChainId = UUID.randomUUID();
        IndexerState noHealthyNode = new IndexerState();
        noHealthyNode.setChainConfigId(noHealthyNodeChainId);
        noHealthyNode.setIndexerType(IndexerState.IndexerType.STARKNET_POLL);
        noHealthyNode.setStatus(IndexerState.IndexerStatus.ACTIVE);
        noHealthyNode.setLastSyncedAt(Instant.now());
        noHealthyNode.setLastSyncedBlock(50L);

        when(indexerStateRepository.findAll()).thenReturn(List.of(lagging, noHealthyNode));
        when(indexerStateRepository.findByStatus(IndexerState.IndexerStatus.ERROR)).thenReturn(List.of());
        when(indexerStateRepository.findByStatusAndLastSyncedAtBefore(any(), any())).thenReturn(List.of());

        ChainConfig laggingChain = new ChainConfig();
        laggingChain.setId(laggingChainId);

        // Two nodes for the lagging chain: an unhealthy one reporting a much higher (stale-check
        // notwithstanding, still disqualified) block, and a healthy one at 100 — the healthy
        // one must win, proving unhealthy/disabled nodes are excluded from the head calculation.
        RpcNode unhealthyNode = new RpcNode();
        unhealthyNode.setChainConfig(laggingChain);
        unhealthyNode.setEnabled(true);
        unhealthyNode.setHealthy(false);
        unhealthyNode.setLatestBlockNumber(500L);

        RpcNode healthyNode = new RpcNode();
        healthyNode.setChainConfig(laggingChain);
        healthyNode.setEnabled(true);
        healthyNode.setHealthy(true);
        healthyNode.setLatestBlockNumber(100L);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(unhealthyNode, healthyNode));

        service.checkIndexerHealth();

        double lagValue = meterRegistry.get("registerwerk_indexer_lag_blocks")
                .tag("chain_config_id", laggingChainId.toString())
                .gauge()
                .value();
        assertThat(lagValue).isEqualTo(10.0); // 100 (healthy head) - 90 (last synced)

        // No healthy node reported for noHealthyNodeChainId at all -> no row, not a misleading 0.
        assertThat(meterRegistry.find("registerwerk_indexer_lag_blocks")
                .tag("chain_config_id", noHealthyNodeChainId.toString())
                .gauge()).isNull();
    }
}
