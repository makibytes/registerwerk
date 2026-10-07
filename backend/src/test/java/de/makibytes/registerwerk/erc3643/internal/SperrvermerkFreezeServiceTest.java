package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.events.HolderBlockFreezeConfirmedEvent;
import de.makibytes.registerwerk.erc3643.events.HolderBlockNotPropagatedEvent;
import de.makibytes.registerwerk.erc3643.events.HolderBlockReleaseFailedEvent;
import de.makibytes.registerwerk.erc3643.internal.HolderBlockFreeze.Status;
import de.makibytes.registerwerk.erc3643.internal.SperrvermerkFreezeDispatcher.Path;
import de.makibytes.registerwerk.erc3643.internal.SperrvermerkFreezeDispatcher.Route;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H5: the Sperrvermerk freeze must reach every deployment it can, and what did not reach the chain must be a recorded,
 * alerted outcome instead of a log line. The register-level block is never touched.
 */
@DisplayName("SperrvermerkFreezeService (H5)")
class SperrvermerkFreezeServiceTest {

    private static final String WALLET = "0x" + "aa".repeat(20);
    private static final String REASON = "eWpG §16 Sperrvermerk: Court order";

    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final AssetLookupPort assets = mock(AssetLookupPort.class);
    private final HolderBlockFreezeRepository freezes = mock(HolderBlockFreezeRepository.class);
    private final HolderBlockRepository blocks = mock(HolderBlockRepository.class);
    private final HolderBlockGate gate = mock(HolderBlockGate.class);
    private final SperrvermerkFreezeDispatcher dispatcher = mock(SperrvermerkFreezeDispatcher.class);
    private final OnChainFrozenReader frozenReader = mock(OnChainFrozenReader.class);
    private final BlockchainApi blockchain = mock(BlockchainApi.class);
    private final EntityTaskPort tasks = mock(EntityTaskPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** In-memory stand-in for the holder_block_freeze table (unique per block, deployment, wallet). */
    private final Map<String, HolderBlockFreeze> store = new LinkedHashMap<>();
    private final UUID issuerId = UUID.randomUUID();
    private SperrvermerkFreezeService service;

    @BeforeEach
    void setUp() {
        service = new SperrvermerkFreezeService(holders, deployments, assets, freezes, blocks, gate, dispatcher,
                frozenReader, blockchain, tasks, events, mock(PlatformTransactionManager.class), meters);
        when(freezes.save(any())).thenAnswer(inv -> {
            HolderBlockFreeze row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            store.put(key(row.getHolderBlockId(), row.getDeploymentId(), row.getWalletAddress()), row);
            return row;
        });
        when(freezes.findByHolderBlockIdAndDeploymentIdAndWalletAddress(any(), any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))));
        when(freezes.findByIdForUpdate(any())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getId())).findFirst());
        when(freezes.findByTxId(any())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getTxId())).toList());
        when(freezes.findByHolderBlockId(any())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getHolderBlockId())).toList());
        when(freezes.findByStatusIn(any())).thenAnswer(inv -> {
            java.util.Collection<Status> wanted = inv.getArgument(0);
            return store.values().stream().filter(r -> wanted.contains(r.getStatus())).toList();
        });
        when(freezes.countByStatus(any())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.getStatus() == inv.getArgument(0)).count());
        when(assets.findById(any())).thenAnswer(inv -> Optional.of(new AssetLookupPort.AssetInfo(
                inv.getArgument(0), "Bond", null, TokenStandard.ERC20, null, null, issuerId, "A-1", "ISSUED")));
        when(dispatcher.route(any())).thenReturn(Route.of(Path.TOKEN_ADMIN));
    }

    private static String key(Object block, Object deployment, Object wallet) {
        return block + "|" + deployment + "|" + wallet;
    }

    private static AssetDeployment deployment(UUID assetId) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setAssetId(assetId);
        d.setChain(Chain.ETHEREUM);
        d.setContractAddress("0x" + "cc".repeat(20));
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return d;
    }

    private static AssetHolder holder(UUID assetId, String wallet) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(assetId);
        h.setWalletAddress(wallet);
        return h;
    }

    /** One asset the wallet holds, with one live deployment. */
    private AssetDeployment registerHolding(String wallet) {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deployment(assetId);
        when(holders.findByWalletAddressIn(List.of(wallet))).thenReturn(List.of(holder(assetId, wallet)));
        when(deployments.findByAssetId(assetId)).thenReturn(List.of(dep));
        when(deployments.findById(dep.getId())).thenReturn(Optional.of(dep));
        return dep;
    }

    private HolderBlock block(HolderBlock.Status status, UUID assetId) {
        HolderBlock b = new HolderBlock();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setWalletAddress(WALLET);
        b.setLegalBasis("Court order");
        b.setStatus(status);
        b.setAssetId(assetId);
        when(blocks.findById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    private HolderBlockFreeze row(UUID blockId, AssetDeployment dep, String wallet, Status status) {
        HolderBlockFreeze r = new HolderBlockFreeze(blockId, dep.getId(), wallet);
        r.setId(UUID.randomUUID());
        r.transitionTo(status, null);
        store.put(key(blockId, dep.getId(), wallet), r);
        return r;
    }

    private HolderBlockFreeze only() {
        assertThat(store).hasSize(1);
        return store.values().iterator().next();
    }

    private ArgumentCaptor<HolderBlockNotPropagatedEvent> notPropagated(int times) {
        ArgumentCaptor<HolderBlockNotPropagatedEvent> captor = ArgumentCaptor.forClass(HolderBlockNotPropagatedEvent.class);
        verify(events, times(times)).publishEvent(captor.capture());
        return captor;
    }

    private void givenTx(UUID txId, String status, String error) {
        when(blockchain.findTransaction(txId)).thenReturn(Optional.of(new BlockchainTransactionView(txId, "0xhash",
                status, "freezeAddress", "ETHEREUM", "TESTNET", "0xtoken", null, null, "system", "SYSTEM", Map.of(), null,
                null, error, Instant.now(), null, null, null, null, null, null, null, null, "TIMEOUT".equals(status))));
    }

    // ── propagation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a freeze is submitted through the dispatcher and recorded as SUBMITTED with its transaction id")
    void propagate_submitsAndRecordsTheTransaction() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        when(dispatcher.freeze(any(), eq(dep), eq(WALLET), eq(REASON))).thenReturn(txId);

        service.propagate(blockId, null, List.of(WALLET), REASON);

        HolderBlockFreeze row = only();
        assertThat(row.getStatus()).isEqualTo(Status.SUBMITTED);
        assertThat(row.getTxId()).isEqualTo(txId);
        assertThat(row.getHolderBlockId()).isEqualTo(blockId);
        assertThat(row.getDeploymentId()).isEqualTo(dep.getId());
        assertThat(row.getAttempts()).isEqualTo(1);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a republished event or the V10 resync does not submit a second freeze")
    void propagate_isIdempotentForSubmittedAndConfirmedRows() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.SUBMITTED);

        service.propagate(blockId, null, List.of(WALLET), REASON);
        store.values().iterator().next().transitionTo(Status.CONFIRMED, null);
        service.propagate(blockId, null, List.of(WALLET), REASON);

        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a submission failure is recorded as FAILED with an audit event, an operator task and the reason - not only logged")
    void propagate_submissionFailureIsRecordedAndReported() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenThrow(new RuntimeException("RPC unavailable"));

        service.propagate(blockId, null, List.of(WALLET), REASON);

        HolderBlockFreeze row = only();
        assertThat(row.getStatus()).isEqualTo(Status.FAILED);
        assertThat(row.getDetail()).contains("RPC unavailable");
        assertThat(row.getAttempts()).isEqualTo(1);
        HolderBlockNotPropagatedEvent event = notPropagated(1).getValue();
        assertThat(event.holderBlockId()).isEqualTo(blockId);
        assertThat(event.payload()).containsEntry("cause", "SUBMISSION_FAILED")
                .containsEntry("walletAddress", WALLET).containsEntry("deploymentId", dep.getId().toString());
        verify(tasks).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED),
                eq(blockId + ":" + dep.getId() + ":" + WALLET), anyString(), any());
        assertThat(meters.get("registerwerk_sperrvermerk_freeze_failed").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a standard/chain without an automated freeze is recorded UNSUPPORTED_ON_CHAIN (event + task), once")
    void propagate_unsupportedIsRecordedNotSkipped() {
        AssetDeployment dep = registerHolding(WALLET);
        dep.setChain(Chain.SOLANA);
        when(dispatcher.route(dep)).thenReturn(new Route(Path.UNSUPPORTED, null, "Solana: no automated freeze"));
        UUID blockId = UUID.randomUUID();

        service.propagate(blockId, null, List.of(WALLET), REASON);
        service.propagate(blockId, null, List.of(WALLET), REASON);

        HolderBlockFreeze row = only();
        assertThat(row.getStatus()).isEqualTo(Status.UNSUPPORTED_ON_CHAIN);
        assertThat(row.getDetail()).contains("Solana");
        HolderBlockNotPropagatedEvent event = notPropagated(1).getValue();
        assertThat(event.payload()).containsEntry("cause", "UNSUPPORTED_ON_CHAIN");
        verify(tasks).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED), anyString(), anyString(), any());
        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
        assertThat(meters.get("registerwerk_sperrvermerk_freeze_unsupported").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a deployment without a live token (PENDING / FAILED) has nothing to freeze and gets no row")
    void propagate_skipsDeploymentsWithoutALiveToken() {
        AssetDeployment dep = registerHolding(WALLET);
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);

        service.propagate(UUID.randomUUID(), null, List.of(WALLET), REASON);

        assertThat(store).isEmpty();
        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an asset-scoped block that matches no register row raises NO_DEPLOYMENT_MATCHED (event + task)")
    void propagate_noDeploymentMatchedIsReported() {
        UUID assetId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        AssetDeployment evm = deployment(assetId);
        when(holders.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of());
        when(deployments.findByAssetId(assetId)).thenReturn(List.of(evm));

        service.propagate(blockId, assetId, List.of(WALLET), REASON);

        HolderBlockNotPropagatedEvent event = notPropagated(1).getValue();
        assertThat(event.holderBlockId()).isEqualTo(blockId);
        assertThat(event.payload()).containsEntry("cause", "NO_DEPLOYMENT_MATCHED");
        verify(tasks).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED), anyString(), anyString(), any());
        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a wallet-wide block on a wallet that holds nothing is a no-op: no event, no task")
    void propagate_walletWideBlockWithoutHoldingsIsANoOp() {
        when(holders.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of());

        service.propagate(UUID.randomUUID(), null, List.of(WALLET), REASON);

        verify(events, never()).publishEvent(any(Object.class));
        verify(tasks, never()).open(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an entity-scoped block freezes every listed wallet")
    void propagate_freezesEveryWallet() {
        String second = "0x" + "bb".repeat(20);
        AssetDeployment depA = registerHolding(WALLET);
        AssetDeployment depB = registerHolding(second);
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.propagate(UUID.randomUUID(), null, List.of(WALLET, second), REASON);

        verify(dispatcher).freeze(any(), eq(depA), eq(WALLET), eq(REASON));
        verify(dispatcher).freeze(any(), eq(depB), eq(second), eq(REASON));
        assertThat(store).hasSize(2);
    }

    @Test
    @DisplayName("an asset-scoped block only looks at that asset's deployments")
    void propagate_scopesToTheBlockedAsset() {
        UUID blocked = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AssetDeployment blockedDep = deployment(blocked);
        when(holders.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(blocked, WALLET), holder(other, WALLET)));
        when(deployments.findByAssetId(blocked)).thenReturn(List.of(blockedDep));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.propagate(UUID.randomUUID(), blocked, List.of(WALLET), REASON);

        verify(dispatcher).freeze(any(), eq(blockedDep), eq(WALLET), eq(REASON));
        verify(deployments, never()).findByAssetId(other);
    }

    @Test
    @DisplayName("a row created concurrently by another run is left alone: no FAILED row, no event")
    void propagate_concurrentInsertIsNotAFailure() {
        registerHolding(WALLET);
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uq_holder_block_freeze")).when(freezes).save(any());

        service.propagate(UUID.randomUUID(), null, List.of(WALLET), REASON);

        assertThat(store).isEmpty();
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ── release ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lifting the last block submits the unfreeze and records RELEASE_SUBMITTED")
    void release_submitsTheUnfreeze() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.CONFIRMED);
        when(gate.isBlockedForAsset(WALLET, dep.getAssetId())).thenReturn(false);
        when(dispatcher.release(any(), eq(dep), eq(WALLET))).thenReturn(txId);

        service.release(blockId, List.of(WALLET));

        HolderBlockFreeze row = only();
        assertThat(row.getStatus()).isEqualTo(Status.RELEASE_SUBMITTED);
        assertThat(row.getTxId()).isEqualTo(txId);
    }

    @Test
    @DisplayName("a freeze another block still covers is kept: nothing is released, the lifted block's row is closed")
    void release_keepsAFreezeAnotherBlockCovers() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.CONFIRMED);
        when(gate.isBlockedForAsset(WALLET, dep.getAssetId())).thenReturn(true);

        service.release(blockId, List.of(WALLET));

        verify(dispatcher, never()).release(any(), any(), anyString());
        assertThat(only().getStatus()).isEqualTo(Status.RELEASED);
        assertThat(only().getDetail()).contains("another blocking Sperrvermerk");
    }

    @Test
    @DisplayName("6-25: lifting the last block releases deployments of ALL the wallet's assets")
    void release_releasesEveryAssetNoRemainingBlockCovers() {
        UUID assetA = UUID.randomUUID();
        UUID assetB = UUID.randomUUID();
        AssetDeployment depA = deployment(assetA);
        AssetDeployment depB = deployment(assetB);
        when(holders.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetA, WALLET), holder(assetB, WALLET)));
        when(deployments.findByAssetId(assetA)).thenReturn(List.of(depA));
        when(deployments.findByAssetId(assetB)).thenReturn(List.of(depB));
        when(gate.isBlockedForAsset(eq(WALLET), any())).thenReturn(false);
        when(dispatcher.release(any(), any(), anyString())).thenReturn(UUID.randomUUID());

        service.release(UUID.randomUUID(), List.of(WALLET));

        verify(dispatcher).release(any(), eq(depA), eq(WALLET));
        verify(dispatcher).release(any(), eq(depB), eq(WALLET));
    }

    @Test
    @DisplayName("a failed release is RELEASE_FAILED with an event and a task; the wallet stays frozen, nothing is forced")
    void release_failureIsRecordedAndReported() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        when(dispatcher.release(any(), any(), anyString())).thenThrow(new RuntimeException("signer unavailable"));

        service.release(blockId, List.of(WALLET));

        HolderBlockFreeze row = only();
        assertThat(row.getStatus()).isEqualTo(Status.RELEASE_FAILED);
        ArgumentCaptor<HolderBlockReleaseFailedEvent> captor = ArgumentCaptor.forClass(HolderBlockReleaseFailedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("walletAddress", WALLET);
        verify(tasks).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED),
                eq(blockId + ":" + dep.getId() + ":" + WALLET + ":release"), anyString(), any());
        assertThat(meters.get("registerwerk_sperrvermerk_release_failed").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unsupported deployment has nothing to release: its alerting row is closed")
    void release_unsupportedDeploymentClosesItsRow() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.UNSUPPORTED_ON_CHAIN);
        when(dispatcher.route(dep)).thenReturn(new Route(Path.UNSUPPORTED, null, "no freeze"));

        service.release(blockId, List.of(WALLET));

        assertThat(only().getStatus()).isEqualTo(Status.RELEASED);
        verify(dispatcher, never()).release(any(), any(), anyString());
    }

    @Test
    @DisplayName("a release is idempotent: RELEASED / RELEASE_SUBMITTED rows are not released again")
    void release_isIdempotent() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.RELEASED);

        service.release(blockId, List.of(WALLET));

        verify(dispatcher, never()).release(any(), any(), anyString());
    }

    @Test
    @DisplayName("rows of the lifted block whose deployment the register no longer leads to are still released")
    void release_followsTheBlocksOwnRows() {
        AssetDeployment dep = registerHolding("0x" + "dd".repeat(20)); // WALLET holds nothing in the register any more
        when(holders.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of());
        UUID blockId = UUID.randomUUID();
        row(blockId, dep, WALLET, Status.CONFIRMED);
        when(gate.isBlockedForAsset(any(), any())).thenReturn(false);
        when(dispatcher.release(any(), eq(dep), eq(WALLET))).thenReturn(UUID.randomUUID());

        service.release(blockId, List.of(WALLET));

        verify(dispatcher).release(any(), eq(dep), eq(WALLET));
    }

    // ── outcome of the transaction ────────────────────────────────────────────

    @Test
    @DisplayName("a SUCCESS transaction confirms the freeze and writes on_chain_freeze_tx_hash")
    void outcome_successConfirmsAndStoresTheTxHash() {
        AssetDeployment dep = registerHolding(WALLET);
        UUID blockId = UUID.randomUUID();
        HolderBlockFreeze row = row(blockId, dep, WALLET, Status.SUBMITTED);
        UUID txId = UUID.randomUUID();
        row.submitted(Status.SUBMITTED, txId, null);
        givenTx(txId, "SUCCESS", null);

        service.syncOutcome(row.getId());

        assertThat(row.getStatus()).isEqualTo(Status.CONFIRMED);
        assertThat(row.getTxHash()).isEqualTo("0xhash");
        assertThat(row.getConfirmedAt()).isNotNull();
        verify(blocks).recordOnChainFreezeTxHash(blockId, "0xhash");
        ArgumentCaptor<HolderBlockFreezeConfirmedEvent> captor = ArgumentCaptor.forClass(HolderBlockFreezeConfirmedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().holderBlockId()).isEqualTo(blockId);
    }

    @Test
    @DisplayName("a FAILED or REPLACED transaction marks the freeze FAILED with an alert and NEVER touches the block")
    void outcome_failedTransactionIsReportedAndTheBlockStaysAuthoritative() {
        for (String verdict : List.of("FAILED", "REPLACED")) {
            store.clear();
            AssetDeployment dep = registerHolding(WALLET);
            UUID blockId = UUID.randomUUID();
            HolderBlockFreeze row = row(blockId, dep, WALLET, Status.SUBMITTED);
            UUID txId = UUID.randomUUID();
            row.submitted(Status.SUBMITTED, txId, null);
            givenTx(txId, verdict, "execution reverted: not registry");

            service.syncOutcome(row.getId());

            assertThat(row.getStatus()).isEqualTo(Status.FAILED);
            assertThat(row.getDetail()).contains(verdict).contains("not registry");
        }
        ArgumentCaptor<HolderBlockNotPropagatedEvent> captor = notPropagated(2);
        assertThat(captor.getAllValues()).allSatisfy(e -> assertThat(e.payload()).containsEntry("cause", "TX_FAILED"));
        verify(tasks, times(2)).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED), anyString(), anyString(), any());
        verify(blocks, never()).save(any());
        verify(blocks, never()).recordOnChainFreezeTxHash(any(), anyString());
    }

    @Test
    @DisplayName("PENDING and TIMEOUT are not verdicts: the freeze stays SUBMITTED")
    void outcome_pendingAndTimeoutKeepWaiting() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlockFreeze row = row(UUID.randomUUID(), dep, WALLET, Status.SUBMITTED);
        UUID txId = UUID.randomUUID();
        row.submitted(Status.SUBMITTED, txId, null);

        givenTx(txId, "PENDING", null);
        service.syncOutcome(row.getId());
        givenTx(txId, "TIMEOUT", null);
        service.syncOutcome(row.getId());

        assertThat(row.getStatus()).isEqualTo(Status.SUBMITTED);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a release transaction SUCCESS -> RELEASED; FAILED -> RELEASE_FAILED with an event")
    void outcome_releaseVerdicts() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlockFreeze ok = row(UUID.randomUUID(), dep, WALLET, Status.RELEASE_SUBMITTED);
        UUID okTx = UUID.randomUUID();
        ok.submitted(Status.RELEASE_SUBMITTED, okTx, null);
        givenTx(okTx, "SUCCESS", null);
        HolderBlockFreeze bad = row(UUID.randomUUID(), dep, WALLET, Status.RELEASE_SUBMITTED);
        UUID badTx = UUID.randomUUID();
        bad.submitted(Status.RELEASE_SUBMITTED, badTx, null);
        givenTx(badTx, "FAILED", "reverted");

        service.syncOutcome(ok.getId());
        service.syncOutcome(bad.getId());

        assertThat(ok.getStatus()).isEqualTo(Status.RELEASED);
        assertThat(bad.getStatus()).isEqualTo(Status.RELEASE_FAILED);
        verify(events).publishEvent(any(HolderBlockReleaseFailedEvent.class));
    }

    @Test
    @DisplayName("syncOutcome is idempotent: a CONFIRMED row is not re-read")
    void outcome_isIdempotent() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlockFreeze row = row(UUID.randomUUID(), dep, WALLET, Status.CONFIRMED);

        service.syncOutcome(row.getId());

        verify(blockchain, never()).findTransaction(any());
    }

    @Test
    @DisplayName("onTransactionStatus resolves the row through the transaction hash; an unrelated transaction is ignored")
    void outcome_transactionStatusEventFindsTheRow() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlockFreeze row = row(UUID.randomUUID(), dep, WALLET, Status.SUBMITTED);
        UUID txId = UUID.randomUUID();
        row.submitted(Status.SUBMITTED, txId, null);
        givenTx(txId, "SUCCESS", null);
        BlockchainTransactionView view = blockchain.findTransaction(txId).orElseThrow();
        when(blockchain.findByTxHash("0xhash")).thenReturn(Optional.of(view));
        when(blockchain.findByTxHash("0xother")).thenReturn(Optional.empty());

        service.onTransactionStatus("0xother");
        assertThat(row.getStatus()).isEqualTo(Status.SUBMITTED);
        service.onTransactionStatus("0xhash");

        assertThat(row.getStatus()).isEqualTo(Status.CONFIRMED);
    }

    // ── sweep ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sweep re-sends a FAILED freeze after its backoff while the block still blocks, not before and not past the attempt cap")
    void sweep_retriesFailedFreezesWithBackoffAndCap() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock blocking = block(HolderBlock.Status.EXPIRY_REVIEW, null);
        HolderBlockFreeze due = row(blocking.getId(), dep, WALLET, Status.FAILED);
        ReflectionTestUtils.setField(due, "attempts", 1);
        ReflectionTestUtils.setField(due, "updatedAt", Instant.now().minus(30, ChronoUnit.MINUTES));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.sweep();

        assertThat(due.getStatus()).isEqualTo(Status.SUBMITTED);

        HolderBlockFreeze fresh = row(blocking.getId(), dep, "0x" + "ee".repeat(20), Status.FAILED);
        HolderBlockFreeze exhausted = row(blocking.getId(), dep, "0x" + "ff".repeat(20), Status.FAILED);
        ReflectionTestUtils.setField(exhausted, "attempts", SperrvermerkFreezeService.MAX_AUTO_ATTEMPTS);
        ReflectionTestUtils.setField(exhausted, "updatedAt", Instant.now().minus(3, ChronoUnit.DAYS));
        service.sweep();
        assertThat(fresh.getStatus()).isEqualTo(Status.FAILED);
        assertThat(exhausted.getStatus()).isEqualTo(Status.FAILED);
        verify(dispatcher, times(1)).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("sweep never re-freezes a FAILED row whose block was lifted meanwhile")
    void sweep_skipsFailedRowsOfLiftedBlocks() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock lifted = block(HolderBlock.Status.LIFTED, null);
        HolderBlockFreeze failed = row(lifted.getId(), dep, WALLET, Status.FAILED);
        ReflectionTestUtils.setField(failed, "updatedAt", Instant.now().minus(1, ChronoUnit.DAYS));

        service.sweep();

        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("sweep retries a failed release and reads the outcome of submitted rows")
    void sweep_retriesReleasesAndSyncsSubmittedRows() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlockFreeze failedRelease = row(UUID.randomUUID(), dep, WALLET, Status.RELEASE_FAILED);
        ReflectionTestUtils.setField(failedRelease, "attempts", 1);
        ReflectionTestUtils.setField(failedRelease, "updatedAt", Instant.now().minus(1, ChronoUnit.HOURS));
        when(gate.isBlockedForAsset(any(), any())).thenReturn(false);
        when(dispatcher.release(any(), any(), anyString())).thenReturn(UUID.randomUUID());
        HolderBlockFreeze submitted = row(UUID.randomUUID(), dep, "0x" + "ee".repeat(20), Status.SUBMITTED);
        UUID txId = UUID.randomUUID();
        submitted.submitted(Status.SUBMITTED, txId, null);
        givenTx(txId, "SUCCESS", null);

        service.sweep();

        assertThat(failedRelease.getStatus()).isEqualTo(Status.RELEASE_SUBMITTED);
        assertThat(submitted.getStatus()).isEqualTo(Status.CONFIRMED);
    }

    // ── nightly reconcile ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reconcile covers ACTIVE and EXPIRY_REVIEW blocks: a missing freeze is re-sent for both")
    void reconcile_resendsMissingFreezesForEveryBlockingBlock() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        HolderBlock review = block(HolderBlock.Status.EXPIRY_REVIEW, null);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active, review));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        SperrvermerkFreezeService.Summary summary = service.reconcile();

        assertThat(summary.resent()).isEqualTo(2);
        verify(dispatcher, times(2)).freeze(any(), eq(dep), eq(WALLET), eq(REASON));
        assertThat(store.values()).extracting(HolderBlockFreeze::getHolderBlockId)
                .containsExactlyInAnyOrder(active.getId(), review.getId());
    }

    @Test
    @DisplayName("reconcile adds the entity's other wallets to an entity-scoped block")
    void reconcile_includesTheEntitysWallets() {
        String second = "0x" + "bb".repeat(20);
        AssetDeployment depB = registerHolding(second);
        registerHolding(WALLET);
        UUID entityId = UUID.randomUUID();
        HolderBlock entityBlock = block(HolderBlock.Status.ACTIVE, null);
        entityBlock.setEntityId(entityId);
        when(holders.findActiveByInvestorId(entityId)).thenReturn(List.of(holder(depB.getAssetId(), second.toUpperCase().replace("0X", "0x"))));
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(entityBlock));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.reconcile();

        assertThat(store.values()).extracting(HolderBlockFreeze::getWalletAddress).containsExactlyInAnyOrder(WALLET, second);
    }

    @Test
    @DisplayName("reconcile re-sends a FAILED freeze")
    void reconcile_resendsFailedFreezes() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        HolderBlockFreeze failed = row(active.getId(), dep, WALLET, Status.FAILED);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.reconcile();

        assertThat(failed.getStatus()).isEqualTo(Status.SUBMITTED);
    }

    @Test
    @DisplayName("reconcile: CONFIRMED + isFrozen true is verified; unreadable is left alone")
    void reconcile_verifiesConfirmedRows() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        HolderBlockFreeze confirmed = row(active.getId(), dep, WALLET, Status.CONFIRMED);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active));

        when(frozenReader.isFrozen(dep, WALLET)).thenReturn(Optional.empty());
        assertThat(service.reconcile().verified()).isZero();
        assertThat(confirmed.getVerifiedAt()).isNull();

        when(frozenReader.isFrozen(dep, WALLET)).thenReturn(Optional.of(true));
        assertThat(service.reconcile().verified()).isEqualTo(1);
        assertThat(confirmed.getVerifiedAt()).isNotNull();
        assertThat(confirmed.getStatus()).isEqualTo(Status.CONFIRMED);
        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("reconcile: CONFIRMED but isFrozen false is DRIFT - counted, reported (event + task) and re-frozen")
    void reconcile_driftIsReportedAndRefrozen() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        HolderBlockFreeze confirmed = row(active.getId(), dep, WALLET, Status.CONFIRMED);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active));
        when(frozenReader.isFrozen(dep, WALLET)).thenReturn(Optional.of(false));
        UUID txId = UUID.randomUUID();
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(txId);

        SperrvermerkFreezeService.Summary summary = service.reconcile();

        assertThat(summary.drift()).isEqualTo(1);
        assertThat(confirmed.getDriftCount()).isEqualTo(1);
        assertThat(confirmed.getStatus()).isEqualTo(Status.SUBMITTED);
        assertThat(confirmed.getTxId()).isEqualTo(txId);
        HolderBlockNotPropagatedEvent event = notPropagated(1).getValue();
        assertThat(event.payload()).containsEntry("cause", "DRIFT");
        verify(tasks).open(eq(issuerId), eq(EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED), anyString(), anyString(), any());
        assertThat(meters.get("registerwerk_sperrvermerk_freeze_drift_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("reconcile keeps reporting an unsupported deployment (gauge) without a new event each night")
    void reconcile_unsupportedStaysCountedButIsNotReRaised() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        row(active.getId(), dep, WALLET, Status.UNSUPPORTED_ON_CHAIN);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active));
        when(dispatcher.route(dep)).thenReturn(new Route(Path.UNSUPPORTED, null, "no freeze"));

        SperrvermerkFreezeService.Summary summary = service.reconcile();

        assertThat(summary.unsupported()).isEqualTo(1);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("reconcile: a failure on one item is counted and does not stop the pass")
    void reconcile_oneBadItemDoesNotStopThePass() {
        AssetDeployment dep = registerHolding(WALLET);
        HolderBlock first = block(HolderBlock.Status.ACTIVE, null);
        HolderBlock second = block(HolderBlock.Status.ACTIVE, null);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(first, second));
        when(freezes.findByHolderBlockIdAndDeploymentIdAndWalletAddress(eq(first.getId()), any(), anyString()))
                .thenThrow(new IllegalStateException("db hiccup"));
        when(dispatcher.freeze(any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());

        SperrvermerkFreezeService.Summary summary = service.reconcile();

        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.resent()).isEqualTo(1);
        verify(dispatcher).freeze(any(), eq(dep), eq(WALLET), eq(REASON));
    }

    @Test
    @DisplayName("reconcile ignores deployments without a live token")
    void reconcile_ignoresNonLiveDeployments() {
        AssetDeployment dep = registerHolding(WALLET);
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
        HolderBlock active = block(HolderBlock.Status.ACTIVE, null);
        when(blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)).thenReturn(List.of(active));

        service.reconcile();

        assertThat(store).isEmpty();
        verify(dispatcher, never()).freeze(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the gauges count rows by status, live")
    void gauges_trackTheTable() {
        AssetDeployment dep = registerHolding(WALLET);
        row(UUID.randomUUID(), dep, WALLET, Status.SUBMITTED);
        row(UUID.randomUUID(), dep, WALLET, Status.FAILED);
        row(UUID.randomUUID(), dep, WALLET, Status.FAILED);

        assertThat(meters.get("registerwerk_sperrvermerk_freeze_pending").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("registerwerk_sperrvermerk_freeze_failed").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("registerwerk_sperrvermerk_freeze_unsupported").gauge().value()).isZero();
        List<String> names = new ArrayList<>();
        meters.getMeters().forEach(m -> names.add(m.getId().getName()));
        assertThat(names).contains("registerwerk_sperrvermerk_release_failed", "registerwerk_sperrvermerk_freeze_drift_total");
    }
}
