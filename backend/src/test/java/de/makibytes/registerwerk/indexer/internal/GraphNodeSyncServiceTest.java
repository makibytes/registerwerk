package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.BlockMeta;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphTransfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphNodeSyncService}'s reorg-safety logic — the crux of the
 * whole finality model, so it gets direct coverage even though a real anvil {@code anvil_reorg}
 * end-to-end run would be the more realistic exercise. That path needs a live anvil node plus a
 * live graph-node subgraph reachable from the test JVM, neither of which Testcontainers can
 * stand up here (graph-node has no official lightweight test image, and this repo's own anvil
 * usage is a docker-compose service, not something Testcontainers manages) — mocking
 * {@link GraphNodeClient} at the collaborator boundary (the same "mock one layer below the unit
 * under test" pattern already used by {@link StarknetTransferSyncServiceTest} and
 * {@code BlockchainTransactionServiceTest}) is the realistic fallback: it exercises the exact
 * same {@code _meta}-hash-comparison algorithm {@link ReorgGuard} runs against a real graph-node,
 * just with the HTTP layer swapped for a scripted collaborator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GraphNodeSyncService — reorg safety")
class GraphNodeSyncServiceTest {

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private IndexerStateRepository indexerStateRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private GraphNodeClient graphNodeClient;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;

    private final ExplorerUrlBuilder explorerUrlBuilder = new ExplorerUrlBuilder();
    private BlockchainTxProperties txProperties;
    private GraphNodeSyncService service;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(2);
        ReorgGuard reorgGuard = new ReorgGuard(tokenTransferRepository);
        service = new GraphNodeSyncService(chainConfigRepository, indexerStateRepository,
                tokenTransferRepository, graphNodeClient, explorerUrlBuilder,
                assetDeploymentRepository, txProperties, reorgGuard);

        when(assetDeploymentRepository.findAll()).thenReturn(List.of());
    }

    private ChainConfig ethereumChain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setIdentifier("ETHEREUM_TESTNET");
        return chain;
    }

    private IndexerState freshState(Long lastSyncedBlock, Long lastFinalBlock) {
        IndexerState s = new IndexerState();
        s.setChainConfigId(chainConfigId);
        s.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
        s.setStatus(IndexerState.IndexerStatus.ACTIVE);
        s.setLastSyncedBlock(lastSyncedBlock);
        s.setLastFinalBlock(lastFinalBlock);
        return s;
    }

    @Test
    @DisplayName("a newly-fetched transfer within the confirmation depth is written PROVISIONAL "
            + "with its block hash recorded")
    void syncChain_writesNewTransferAsProvisional_withinConfirmationDepth() {
        ChainConfig chain = ethereumChain();
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE)).thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Head at 100, transfer at block 100 -> depth = 100-100+1 = 1 < required(2) -> PROVISIONAL.
        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(100, "0xheadhash", false)));
        when(graphNodeClient.fetchMeta(chain, 100L))
                .thenReturn(Optional.of(new BlockMeta(100, "0xblockhash100", false)));

        GraphTransfer transfer = new GraphTransfer("0xtx1-0", "0xtoken", "0xfrom", "0xto",
                null, "5", "TRANSFER", 100L, 1_700_000_000L, "0xtx1", 0L);
        when(graphNodeClient.fetchTransfers(eq(chain), eq(0L), eq(GraphNodeSyncService.PAGE_SIZE), eq(0)))
                .thenReturn(List.of(transfer));
        when(tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(chainConfigId, "0xtx1", 0))
                .thenReturn(false);
        // No provisional backlog to re-verify this tick.
        when(tokenTransferRepository.findDistinctProvisionalBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();
        assertThat(saved.getFinalityStatus()).isEqualTo(TokenTransfer.FinalityStatus.PROVISIONAL);
        assertThat(saved.getBlockHash()).isEqualTo("0xblockhash100");
        assertThat(saved.getBlockNumber()).isEqualTo(100L);

        // loadOrCreateState's own save (creating the fresh row) plus the end-of-syncChain save.
        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository, org.mockito.Mockito.atLeastOnce()).save(stateCaptor.capture());
        IndexerState savedState = stateCaptor.getValue();
        assertThat(savedState.getLastSyncedBlock()).isEqualTo(100L);
        // finalThrough = head(100) - required(2) = 98
        assertThat(savedState.getLastFinalBlock()).isEqualTo(98L);
        assertThat(savedState.getStatus()).isEqualTo(IndexerState.IndexerStatus.ACTIVE);
    }

    @Test
    @DisplayName("a PROVISIONAL block whose hash still matches once it clears the confirmation "
            + "depth is flipped to FINAL")
    void syncChain_flipsProvisionalToFinal_onceConfirmationDepthCleared() {
        ChainConfig chain = ethereumChain();
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE))
                .thenReturn(Optional.of(freshState(200L, 40L)));
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(200, "0xheadhash", false)));
        // No new transfers this tick.
        when(graphNodeClient.fetchTransfers(eq(chain), eq(201L), anyInt(), eq(0)))
                .thenReturn(List.of());

        // One still-PROVISIONAL block from an earlier tick, now deep enough to finalize.
        when(tokenTransferRepository.findDistinctProvisionalBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(graphNodeClient.fetchMeta(chain, 50L))
                .thenReturn(Optional.of(new BlockMeta(50, "0xhash50", false)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xhash50")); // matches -> not orphaned
        when(tokenTransferRepository.markFinalAtBlock(chainConfigId, 50L)).thenReturn(3);

        service.syncChain(chain);

        verify(tokenTransferRepository).markFinalAtBlock(chainConfigId, 50L);
        verify(tokenTransferRepository, never()).markOrphanedFromBlock(any(), any());

        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(stateCaptor.capture());
        // No reorg and no new transfers -> cursor stays exactly where it was (fromBlock - 1).
        assertThat(stateCaptor.getValue().getLastSyncedBlock()).isEqualTo(200L);
    }

    @Test
    @DisplayName("a PROVISIONAL block whose freshly re-fetched hash no longer matches triggers a "
            + "reorg: rows are ORPHANED (never deleted) and the cursor rewinds to the fork point")
    void syncChain_detectsReorg_orphansRowsAndRewindsCursor() {
        ChainConfig chain = ethereumChain();
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE))
                .thenReturn(Optional.of(freshState(100L, 60L)));
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(200, "0xheadhash", false)));
        when(graphNodeClient.fetchTransfers(eq(chain), eq(101L), anyInt(), eq(0)))
                .thenReturn(List.of());

        when(tokenTransferRepository.findDistinctProvisionalBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(graphNodeClient.fetchMeta(chain, 50L))
                .thenReturn(Optional.of(new BlockMeta(50, "0xNEWHASH", false)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xOLDHASH")); // mismatch -> orphaned
        when(tokenTransferRepository.existsFinalAtOrAfter(chainConfigId, 50L)).thenReturn(false);
        when(tokenTransferRepository.markOrphanedFromBlock(chainConfigId, 50L)).thenReturn(4);

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        verify(tokenTransferRepository).markOrphanedFromBlock(chainConfigId, 50L);
        verify(tokenTransferRepository, never()).markFinalAtBlock(any(), any());

        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(stateCaptor.capture());
        IndexerState savedState = stateCaptor.getValue();
        // Rewound to forkBlock(50) - 1 = 49. lastFinalBlock (60) was >= forkBlock, so it is
        // clamped down too — a reorg deeper than the confirmation-depth policy guaranteed against.
        assertThat(savedState.getLastSyncedBlock()).isEqualTo(49L);
        assertThat(savedState.getLastFinalBlock()).isEqualTo(49L);
    }
}
