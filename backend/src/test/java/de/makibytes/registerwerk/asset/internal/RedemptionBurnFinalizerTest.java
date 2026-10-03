package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetRedemptionIncompleteEvent;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup.IndexedTransfer;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b C7: the asset becomes REDEEMED only after EVERY burn it dispatched is final (final receipt plus the indexed
 * FINALIZED BURN transfer); a reverted burn leaves it REDEMPTION_PENDING with an operator-alerting event.
 */
@DisplayName("RedemptionBurnFinalizer (C7)")
class RedemptionBurnFinalizerTest {

    private static final String WALLET = "0x00000000000000000000000000000000000000aa";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final AssetRedemptionBurnRepository burns = mock(AssetRedemptionBurnRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final BlockchainApi blockchain = mock(BlockchainApi.class);
    private final IndexedTransferLookup indexed = mock(IndexedTransferLookup.class);
    private final AssetLifecycleService lifecycle = mock(AssetLifecycleService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private RedemptionBurnFinalizer finalizer;

    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        finalizer = new RedemptionBurnFinalizer(burns, assets, blockchain, indexed, lifecycle, events,
                new IsolatedTransactionExecutor(mock(PlatformTransactionManager.class)), new SimpleMeterRegistry());
        when(burns.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AssetRedemptionBurn burn(String amount, AssetRedemptionBurn.Status status) {
        AssetRedemptionBurn b = new AssetRedemptionBurn();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setAssetId(assetId);
        b.setDeploymentId(UUID.randomUUID());
        b.setWalletAddress(WALLET);
        b.setAmount(new BigDecimal(amount));
        b.setTxId(UUID.randomUUID());
        b.setStatus(status);
        when(burns.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    private void givenTx(AssetRedemptionBurn b, String hash, String status, String error) {
        when(blockchain.findTransaction(b.getTxId())).thenReturn(Optional.of(new BlockchainTransactionView(b.getTxId(),
                hash, status, "forceBurn", "ETHEREUM", "TESTNET", "0xtoken", b.getDeploymentId(), assetId, "op",
                "REGISTRY_ADMIN", Map.of(), null, null, error, Instant.now(), null, null, null, null, null, null, null,
                null, "TIMEOUT".equals(status))));
    }

    private void givenIndexedBurn(String hash, String from, String amount) {
        when(indexed.finalizedTransfers(hash)).thenReturn(List.of(
                new IndexedTransfer(UUID.randomUUID(), from, ZERO, new BigDecimal(amount), false, true)));
    }

    @Test
    @DisplayName("final receipt AND the FINALIZED BURN transfer of that wallet for that amount: the burn is CONFIRMED")
    void confirmsOnReceiptAndIndexedBurn() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.SUBMITTED);
        givenTx(b, "0xburn1", "SUCCESS", null);
        givenIndexedBurn("0xburn1", WALLET, "500");

        finalizer.reconcileBurn(b.getId());

        assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.CONFIRMED);
        assertThat(b.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("a successful receipt without the indexed BURN stays SUBMITTED")
    void receiptWithoutIndexedBurnStaysSubmitted() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.SUBMITTED);
        givenTx(b, "0xburn1", "SUCCESS", null);
        when(indexed.finalizedTransfers("0xburn1")).thenReturn(List.of());

        finalizer.reconcileBurn(b.getId());

        assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.SUBMITTED);
    }

    @Test
    @DisplayName("a reverted burn: FAILED with the reason and an operator-alerting AssetRedemptionIncompleteEvent; the asset is never completed")
    void revertedBurnIsFailedAndReported() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.SUBMITTED);
        givenTx(b, "0xburn1", "FAILED", "Transaction reverted on-chain");

        finalizer.reconcileBurn(b.getId());
        finalizer.tryComplete(assetId);

        assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.FAILED);
        assertThat(b.getFailureReason()).contains("FAILED").contains("reverted");
        verify(events).publishEvent(any(AssetRedemptionIncompleteEvent.class));
        verify(lifecycle, never()).completeRedemption(any(), anyInt());
    }

    @Test
    @DisplayName("a TIMEOUT burn is not a failure (it may still be mined): it keeps waiting, nothing is reported")
    void timeoutBurnKeepsWaiting() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.SUBMITTED);
        givenTx(b, "0xburn1", "TIMEOUT", "not mined");

        finalizer.reconcileBurn(b.getId());

        assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.SUBMITTED);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("the asset is completed only when EVERY burn of it is confirmed")
    void completesOnlyWhenEveryBurnIsConfirmed() {
        AssetRedemptionBurn done = burn("60", AssetRedemptionBurn.Status.CONFIRMED);
        AssetRedemptionBurn waiting = burn("40", AssetRedemptionBurn.Status.SUBMITTED);
        when(burns.findByAssetId(assetId)).thenReturn(List.of(done, waiting));

        finalizer.tryComplete(assetId);
        verify(lifecycle, never()).completeRedemption(any(), anyInt());

        waiting.setStatus(AssetRedemptionBurn.Status.CONFIRMED);
        finalizer.tryComplete(assetId);
        verify(lifecycle).completeRedemption(assetId, 2);
    }

    @Test
    @DisplayName("the transaction-status event reconciles the burn that waits for that transaction and completes the asset")
    void statusEventConfirmsAndCompletes() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.SUBMITTED);
        givenTx(b, "0xburn1", "SUCCESS", null);
        givenIndexedBurn("0xburn1", WALLET, "500");
        when(blockchain.findByTxHash("0xburn1")).thenReturn(Optional.of(new BlockchainTransactionView(b.getTxId(), "0xburn1",
                "SUCCESS", "forceBurn", null, null, null, null, null, null, null, Map.of(), null, null, null, null, null,
                null, null, null, null, null, null, null, false)));
        when(burns.findByTxId(b.getTxId())).thenReturn(Optional.of(b));
        when(burns.findByAssetId(assetId)).thenReturn(List.of(b));

        finalizer.onTransactionStatus(new BlockchainTxStatusEvent(b.getDeploymentId(), null, "REGISTRY_ADMIN", "SUCCESS",
                Map.of("txHash", "0xburn1")));

        verify(lifecycle).completeRedemption(assetId, 1);
    }

    @Test
    @DisplayName("idempotent: a burn that is already CONFIRMED / FAILED is not looked at again")
    void settledBurnsAreIgnored() {
        AssetRedemptionBurn b = burn("500", AssetRedemptionBurn.Status.CONFIRMED);

        finalizer.reconcileBurn(b.getId());

        verify(blockchain, never()).findTransaction(any());
    }
}
