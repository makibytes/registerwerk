package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private BlockFinalityFeed blockFinalityFeed;
    @Mock private de.makibytes.registerwerk.finality.api.BlockFinalityPort blockFinalityPort;
    @Mock private de.makibytes.registerwerk.finality.api.ChainEffectRecorder chainEffectRecorder;
    @Mock private de.makibytes.registerwerk.chain.api.RpcNodeRepository rpcNodeRepository;
    @Mock private ChaincacheFinalityProbe chaincacheFinalityProbe;

    private final ExplorerUrlBuilder explorerUrlBuilder = new ExplorerUrlBuilder();
    private BlockchainTxProperties txProperties;
    private GraphNodeSyncService service;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(2);
        ReorgGuard reorgGuard = new ReorgGuard(tokenTransferRepository, blockFinalityFeed, blockFinalityPort,
                chainEffectRecorder, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        // clientRegistry is unused unless a chain is configured TAG_BASED — every ethereumChain()
        // fixture below defaults to DEPTH_BASED (ChainConfig's own field default), so the
        // existing depth-only tests never touch it.
        service = new GraphNodeSyncService(chainConfigRepository, indexerStateRepository,
                tokenTransferRepository, graphNodeClient, explorerUrlBuilder,
                assetDeploymentRepository, txProperties, reorgGuard, clientRegistry,
                rpcNodeRepository, chaincacheFinalityProbe);

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
        // No provisional backlog to re-verify this tick.
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        TokenTransfer saved = captor.getValue();
        assertThat(saved.getFinalityStatus()).isEqualTo(FinalityLevel.PROVISIONAL);
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
    @DisplayName("A -> B stores the same transaction/log as a distinct block occurrence")
    void syncChain_reminedLogInReplacementBlock_createsDistinctOccurrence() {
        ChainConfig chain = ethereumChain();
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE)).thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(100, "0xhead", false)));
        when(graphNodeClient.fetchMeta(chain, 100L))
                .thenReturn(Optional.of(new BlockMeta(100, "0xBBBB", false)));

        GraphTransfer replacementB = new GraphTransfer("same-log", "0xtoken", "0xfrom", "0xto",
                null, "5", "TRANSFER", 100L, 1_700_000_000L, "0xsame-tx", 7L);
        when(graphNodeClient.fetchTransfers(eq(chain), eq(0L), eq(GraphNodeSyncService.PAGE_SIZE), eq(0)))
                .thenReturn(List.of(replacementB));
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        verify(tokenTransferRepository)
                .findByChainConfigIdAndTxHashAndLogIndexAndBlockHashAndOccurredAt(
                        chainConfigId, "0xsame-tx", 7, "0xbbbb", Instant.ofEpochSecond(1_700_000_000L));
        ArgumentCaptor<TokenTransfer> transfer = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(transfer.capture());
        assertThat(transfer.getValue().getBlockHash()).isEqualTo("0xbbbb");
        verify(tokenTransferRepository, never())
                .existsByChainConfigIdAndTxHashAndLogIndex(any(), any(), any());
    }

    @Test
    @DisplayName("A -> B -> A reactivates exact orphaned A with fresh finality instead of inserting it")
    void syncChain_sameBlockReturns_reactivatesOrphanedOccurrence() {
        ChainConfig chain = ethereumChain();
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE)).thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(100, "0xhead", false)));
        when(graphNodeClient.fetchMeta(chain, 100L))
                .thenReturn(Optional.of(new BlockMeta(100, "0xAAAA", false)));

        Instant occurredAt = Instant.ofEpochSecond(1_700_000_000L);
        TokenTransfer orphanedA = new TokenTransfer();
        orphanedA.setId(UUID.randomUUID());
        orphanedA.setChainConfigId(chainConfigId);
        orphanedA.setTxHash("0xaabb");
        orphanedA.setLogIndex(7);
        orphanedA.setBlockHash("0xaaaa");
        orphanedA.setOccurredAt(occurredAt);
        orphanedA.setFinalityStatus(FinalityLevel.ORPHANED);

        GraphTransfer returnedA = new GraphTransfer("same-log", "0xtoken", "0xfrom", "0xto",
                null, "5", "TRANSFER", 100L, occurredAt.getEpochSecond(), "0xAABB", 7L);
        when(graphNodeClient.fetchTransfers(eq(chain), eq(0L), eq(GraphNodeSyncService.PAGE_SIZE), eq(0)))
                .thenReturn(List.of(returnedA));
        when(tokenTransferRepository
                .findByChainConfigIdAndTxHashAndLogIndexAndBlockHashAndOccurredAt(
                        chainConfigId, "0xaabb", 7, "0xaaaa", occurredAt))
                .thenReturn(Optional.of(orphanedA));
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> transfer = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(transfer.capture());
        assertThat(transfer.getValue()).isSameAs(orphanedA);
        assertThat(orphanedA.getFinalityStatus()).isEqualTo(FinalityLevel.PROVISIONAL);
        assertThat(orphanedA.getBlockHash()).isEqualTo("0xaaaa");
        assertThat(orphanedA.getTxHash()).isEqualTo("0xaabb");
        assertThat(orphanedA.getContractAddress()).isEqualTo("0xtoken");
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
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(graphNodeClient.fetchMeta(chain, 50L))
                .thenReturn(Optional.of(new BlockMeta(50, "0xhash50", false)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xhash50")); // matches -> not orphaned
        when(tokenTransferRepository.markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED)).thenReturn(3);

        service.syncChain(chain);

        // This depth threshold has safeConfirmations clamped equal to requiredConfirmations (see
        // BlockchainTxProperties#safeConfirmationsFor), so a PROVISIONAL row goes straight to
        // FINALIZED in one probe — the SAFE-sourced promotion call finds no matching row (0, the
        // Mockito default for an unstubbed int-returning call) and is never separately verified.
        verify(tokenTransferRepository).markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED);
        verify(tokenTransferRepository, never()).markOrphanedFromBlock(any(), any());
        // The finality module's own ledger must be told about this promotion too - this is what
        // makes "nothing reacts to a block's finality changing" no longer true.
        verify(blockFinalityFeed).recordObservation(chainConfigId, 50L, "0xhash50", FinalityLevel.FINALIZED);

        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(stateCaptor.capture());
        // No reorg and no new transfers -> cursor stays exactly where it was (fromBlock - 1).
        assertThat(stateCaptor.getValue().getLastSyncedBlock()).isEqualTo(200L);
    }

    @Test
    @DisplayName("a CHAINCACHE-sourced chain re-verifies its unsettled window via "
            + "ChaincacheFinalityProbe, never via RPC self-probing")
    void syncChain_chaincacheFinalitySource_usesChaincacheProbeNotRpcSelfProbe() {
        ChainConfig chain = ethereumChain();
        org.springframework.test.util.ReflectionTestUtils.setField(
                chain, "finalitySource", ChainConfig.FinalitySource.CHAINCACHE);
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE))
                .thenReturn(Optional.of(freshState(200L, 40L)));
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(200, "0xheadhash", false)));
        when(graphNodeClient.fetchTransfers(eq(chain), eq(201L), anyInt(), eq(0)))
                .thenReturn(List.of());

        RpcNode chaincacheNode = new RpcNode();
        chaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);
        chaincacheNode.setManagementUrl("http://chaincache-sepolia:8080");
        chaincacheNode.setRemoteChainKey("sepolia");
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(chainConfigId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(List.of(chaincacheNode));

        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(chaincacheFinalityProbe.observe(chaincacheNode, 50L))
                .thenReturn(Optional.of(new ChaincacheFinalityProbe.Observation("0xhash50", ReorgGuard.ProbeResult.FINALIZED)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xhash50")); // matches -> not orphaned
        when(tokenTransferRepository.markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED)).thenReturn(1);

        service.syncChain(chain);

        verify(chaincacheFinalityProbe).observe(chaincacheNode, 50L);
        verify(graphNodeClient, never()).fetchMeta(chain, 50L);
        verify(tokenTransferRepository).markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED);
        verify(tokenTransferRepository, never()).markOrphanedFromBlock(any(), any());
    }

    @Test
    @DisplayName("a CHAINCACHE-sourced chain with no enabled chaincache node falls back to RPC "
            + "self-probing rather than skipping reorg verification entirely")
    void syncChain_chaincacheFinalitySource_noNode_fallsBackToRpcSelfProbe() {
        ChainConfig chain = ethereumChain();
        org.springframework.test.util.ReflectionTestUtils.setField(
                chain, "finalitySource", ChainConfig.FinalitySource.CHAINCACHE);
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE))
                .thenReturn(Optional.of(freshState(200L, 40L)));
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(200, "0xheadhash", false)));
        when(graphNodeClient.fetchTransfers(eq(chain), eq(201L), anyInt(), eq(0)))
                .thenReturn(List.of());
        when(rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(chainConfigId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(List.of());

        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(graphNodeClient.fetchMeta(chain, 50L))
                .thenReturn(Optional.of(new BlockMeta(50, "0xhash50", false)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xhash50"));
        when(tokenTransferRepository.markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED)).thenReturn(1);

        service.syncChain(chain);

        verify(chaincacheFinalityProbe, never()).observe(any(), anyLong());
        verify(graphNodeClient).fetchMeta(chain, 50L);
        verify(tokenTransferRepository).markLevelAtBlock(
                chainConfigId, 50L, FinalityLevel.PROVISIONAL, FinalityLevel.FINALIZED);
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

        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of(50L));
        when(graphNodeClient.fetchMeta(chain, 50L))
                .thenReturn(Optional.of(new BlockMeta(50, "0xNEWHASH", false)));
        when(tokenTransferRepository.findDistinctBlockHashesAt(chainConfigId, 50L))
                .thenReturn(List.of("0xOLDHASH")); // mismatch -> orphaned
        when(tokenTransferRepository.existsFinalizedAtOrAfter(chainConfigId, 50L)).thenReturn(false);
        when(tokenTransferRepository.markOrphanedFromBlock(chainConfigId, 50L)).thenReturn(4);
        UUID affectedAssetId = UUID.randomUUID();
        when(tokenTransferRepository.findDistinctAssetIdsAtOrAfter(chainConfigId, 50L))
                .thenReturn(List.of(affectedAssetId));

        service.syncChain(chain);

        verify(tokenTransferRepository, never()).save(any());
        verify(tokenTransferRepository).markOrphanedFromBlock(chainConfigId, 50L);
        verify(tokenTransferRepository, never()).markLevelAtBlock(any(), any(), any(), any());
        // The retraction must reach the finality module's ledger too, carrying the fresh
        // (replacement) hash and the count of token_transfer rows this reorg orphaned.
        verify(blockFinalityFeed).recordRetraction(chainConfigId, 50L, "0xNEWHASH", 4);
        // And it must actually correct balances: every asset with a transfer at/after the fork
        // block gets a HOLDER_BALANCE_SYNCED effect journalled and immediately compensated.
        ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> effectCaptor =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordAndCompensate(effectCaptor.capture());
        assertThat(effectCaptor.getValue().entityId()).isEqualTo(affectedAssetId);
        assertThat(effectCaptor.getValue().effectType()).isEqualTo("HOLDER_BALANCE_SYNCED");

        ArgumentCaptor<IndexerState> stateCaptor = ArgumentCaptor.forClass(IndexerState.class);
        verify(indexerStateRepository).save(stateCaptor.capture());
        IndexerState savedState = stateCaptor.getValue();
        // Rewound to forkBlock(50) - 1 = 49. lastFinalBlock (60) was >= forkBlock, so it is
        // clamped down too — a reorg deeper than the confirmation-depth policy guaranteed against.
        assertThat(savedState.getLastSyncedBlock()).isEqualTo(49L);
        assertThat(savedState.getLastFinalBlock()).isEqualTo(49L);
    }

    // ── TAG_BASED finality model ─────────────────────────────────────────────

    private org.web3j.protocol.Web3j stubFinalizedTag(ChainConfig chain, long finalizedBlockNumber) {
        org.web3j.protocol.Web3j web3j =
                mock(org.web3j.protocol.Web3j.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(clientRegistry.getEvmClientByIdentifier(chain.getIdentifier())).thenReturn(web3j);
        org.web3j.protocol.core.methods.response.EthBlock finalizedResponse = mock(
                org.web3j.protocol.core.methods.response.EthBlock.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(finalizedResponse.hasError()).thenReturn(false);
        when(finalizedResponse.getBlock().getNumber()).thenReturn(java.math.BigInteger.valueOf(finalizedBlockNumber));
        try {
            when(web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.FINALIZED, false).send())
                    .thenReturn(finalizedResponse);
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        return web3j;
    }

    @Test
    @DisplayName("TAG_BASED chain: a transfer past the depth threshold but below the node's "
            + "finalized tag stays PROVISIONAL — the tag overrides depth, not the other way round")
    void syncChain_tagBased_belowFinalizedTag_staysProvisionalDespiteDepth() {
        ChainConfig chain = ethereumChain();
        chain.setFinalityModel(ChainConfig.FinalityModel.TAG_BASED);
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE)).thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Head at 100, transfer at block 90 -> depth = 11 >= required(2), so DEPTH_BASED would
        // finalize it; but the node's finalized tag is only at 80, so TAG_BASED must not.
        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(100, "0xheadhash", false)));
        stubFinalizedTag(chain, 80L);

        GraphTransfer transfer = new GraphTransfer("0xtx1-0", "0xtoken", "0xfrom", "0xto",
                null, "5", "TRANSFER", 90L, 1_700_000_000L, "0xtx1", 0L);
        when(graphNodeClient.fetchTransfers(eq(chain), eq(0L), eq(GraphNodeSyncService.PAGE_SIZE), eq(0)))
                .thenReturn(List.of(transfer));
        when(graphNodeClient.fetchMeta(chain, 90L))
                .thenReturn(Optional.of(new BlockMeta(90, "0xblockhash90", false)));
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getFinalityStatus()).isEqualTo(FinalityLevel.PROVISIONAL);
    }

    @Test
    @DisplayName("TAG_BASED chain: a transfer first seen FINALIZED still persists its exact "
            + "normalized block hash for typed-reorg selection")
    void syncChain_tagBased_atOrBelowFinalizedTag_writesFinalImmediately() {
        ChainConfig chain = ethereumChain();
        chain.setFinalityModel(ChainConfig.FinalityModel.TAG_BASED);
        when(indexerStateRepository.findByChainConfigIdAndIndexerType(
                chainConfigId, IndexerState.IndexerType.GRAPH_NODE)).thenReturn(Optional.empty());
        when(indexerStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(graphNodeClient.fetchMeta(chain, null))
                .thenReturn(Optional.of(new BlockMeta(100, "0xheadhash", false)));
        stubFinalizedTag(chain, 80L);

        GraphTransfer transfer = new GraphTransfer("0xtx2-0", "0xtoken", "0xfrom", "0xto",
                null, "5", "TRANSFER", 70L, 1_700_000_000L, "0xtx2", 0L);
        when(graphNodeClient.fetchTransfers(eq(chain), eq(0L), eq(GraphNodeSyncService.PAGE_SIZE), eq(0)))
                .thenReturn(List.of(transfer));
        when(graphNodeClient.fetchMeta(chain, 70L))
                .thenReturn(Optional.of(new BlockMeta(70, "0xABCDEF", false)));
        when(tokenTransferRepository.findDistinctUnsettledBlocks(chainConfigId)).thenReturn(List.of());

        service.syncChain(chain);

        ArgumentCaptor<TokenTransfer> captor = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(tokenTransferRepository).save(captor.capture());
        assertThat(captor.getValue().getFinalityStatus()).isEqualTo(FinalityLevel.FINALIZED);
        assertThat(captor.getValue().getBlockHash()).isEqualTo("0xabcdef");
        verify(graphNodeClient).fetchMeta(chain, 70L);
    }
}
