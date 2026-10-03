package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettledEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettlementFailedEvent;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b C7: a subscription order is SETTLED on the chain OUTCOME - a final mint receipt AND the indexed FINALIZED
 * MINT transfer - never on submission; a reverted / replaced mint is SETTLEMENT_FAILED (audited, retryable); a timed-out
 * mint is neither (it may still execute).
 */
@DisplayName("SubscriptionSettlementFinalizer (C7)")
class SubscriptionSettlementFinalizerTest {

    private static final String WALLET = "0x00000000000000000000000000000000000000aa";
    private static final String TX_HASH = "0xmint";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final SubscriptionOrderRepository orders = mock(SubscriptionOrderRepository.class);
    private final BlockchainApi blockchain = mock(BlockchainApi.class);
    private final IndexedTransferLookup indexed = mock(IndexedTransferLookup.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private SubscriptionSettlementFinalizer finalizer;

    private final UUID orderId = UUID.randomUUID();
    private final UUID txId = UUID.randomUUID();
    private SubscriptionOrder order;

    @BeforeEach
    void setUp() {
        finalizer = new SubscriptionSettlementFinalizer(orders, blockchain, indexed, events,
                new IsolatedTransactionExecutor(mock(PlatformTransactionManager.class)), new SimpleMeterRegistry());
        order = new SubscriptionOrder();
        order.setAssetId(UUID.randomUUID());
        order.setInvestorEntityId(UUID.randomUUID());
        order.setWalletAddress(WALLET);
        order.setAllocatedAmount(new BigDecimal("800"));
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        order.setSettlementTxId(txId);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static BlockchainTransactionView tx(UUID id, String status, String error) {
        return new BlockchainTransactionView(id, TX_HASH, status, "mint", "ETHEREUM", "TESTNET", "0xtoken",
                UUID.randomUUID(), UUID.randomUUID(), "op", "REGISTRY_ADMIN", Map.of(), null, null, error,
                Instant.now(), null, null, null, null, null, null, null, null, "TIMEOUT".equals(status));
    }

    private void givenTx(String status, String error) {
        when(blockchain.findTransaction(txId)).thenReturn(Optional.of(tx(txId, status, error)));
    }

    private void givenIndexedMint(String to, String amount) {
        when(indexed.finalizedTransfers(TX_HASH)).thenReturn(List.of(
                new IndexedTransfer(UUID.randomUUID(), ZERO, to, new BigDecimal(amount), true, false)));
    }

    @Test
    @DisplayName("final receipt AND the FINALIZED MINT transfer to the investor's wallet for the allocated amount: SETTLED, audited")
    void settlesOnReceiptAndIndexedMint() {
        givenTx("SUCCESS", null);
        givenIndexedMint(WALLET, "800");

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLED);
        assertThat(order.getSettledAt()).isNotNull();
        verify(events).publishEvent(any(SubscriptionOrderSettledEvent.class));
    }

    @Test
    @DisplayName("a successful receipt without the indexed mint is not enough: the order stays SETTLEMENT_PENDING")
    void receiptWithoutIndexedMintStaysPending() {
        givenTx("SUCCESS", null);
        when(indexed.finalizedTransfers(TX_HASH)).thenReturn(List.of());

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        verify(events, never()).publishEvent(any(SubscriptionOrderSettledEvent.class));
    }

    @Test
    @DisplayName("an indexed mint to another wallet or of another amount does not settle the order")
    void wrongWalletOrAmountDoesNotSettle() {
        givenTx("SUCCESS", null);
        givenIndexedMint("0x00000000000000000000000000000000000000bb", "800");
        finalizer.reconcile(orderId);
        givenIndexedMint(WALLET, "799");
        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
    }

    @Test
    @DisplayName("a reverted mint: SETTLEMENT_FAILED with the reason, operator-alerting event, never SETTLED")
    void revertedMintIsFailedAndAudited() {
        givenTx("FAILED", "Transaction reverted on-chain");

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_FAILED);
        assertThat(order.getSettlementFailedAt()).isNotNull();
        assertThat(order.getSettlementFailureReason()).contains("FAILED").contains("reverted");
        assertThat(order.getSettledAt()).isNull();
        verify(events).publishEvent(any(SubscriptionOrderSettlementFailedEvent.class));
        verify(events, never()).publishEvent(any(SubscriptionOrderSettledEvent.class));
    }

    @Test
    @DisplayName("a replaced mint (its nonce was consumed by another transaction) can never mine: SETTLEMENT_FAILED too")
    void replacedMintIsFailed() {
        givenTx("REPLACED", "Transaction replaced: cancel replacement");

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_FAILED);
    }

    @Test
    @DisplayName("a TIMEOUT mint is NOT a failure - it may still be mined, so retrying could mint twice: the order waits")
    void timeoutIsNotAFailure() {
        givenTx("TIMEOUT", "not mined within 600s");

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        verify(events, never()).publishEvent(any(SubscriptionOrderSettlementFailedEvent.class));
    }

    @Test
    @DisplayName("a PENDING mint keeps the order SETTLEMENT_PENDING")
    void pendingMintWaits() {
        givenTx("PENDING", null);

        finalizer.reconcile(orderId);

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_PENDING);
    }

    @Test
    @DisplayName("idempotent: an order that is already SETTLED / FAILED is left alone by a redelivered trigger")
    void alreadyFinalOrdersAreIgnored() {
        order.setStatus(SubscriptionOrder.Status.SETTLED);
        finalizer.reconcile(orderId);
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_FAILED);
        finalizer.reconcile(orderId);

        verify(blockchain, never()).findTransaction(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("the transaction-status event triggers reconciliation of the order that waits for that very transaction")
    void statusEventTriggersReconcile() {
        givenTx("FAILED", "Transaction reverted on-chain");
        when(blockchain.findByTxHash(TX_HASH)).thenReturn(Optional.of(tx(txId, "FAILED", null)));
        when(orders.findBySettlementTxId(txId)).thenReturn(Optional.of(order));
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_PENDING);
        // findByIdForUpdate resolves by the order's id
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);

        finalizer.onTransactionStatus(new BlockchainTxStatusEvent(UUID.randomUUID(), null, "REGISTRY_ADMIN", "FAILED",
                Map.of("txHash", TX_HASH, "methodName", "mint")));

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_FAILED);
    }

    @Test
    @DisplayName("the sweep reconciles every pending order in its own transaction; one failure does not stop the rest")
    void sweepIsolatesFailures() {
        SubscriptionOrder broken = new SubscriptionOrder();
        org.springframework.test.util.ReflectionTestUtils.setField(broken, "id", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", orderId);
        when(orders.findByStatusOrderBySubmittedAtAsc(SubscriptionOrder.Status.SETTLEMENT_PENDING))
                .thenReturn(List.of(broken, order));
        when(orders.findByIdForUpdate(broken.getId())).thenThrow(new IllegalStateException("db hiccup"));
        givenTx("FAILED", "Transaction reverted on-chain");

        finalizer.sweep();

        assertThat(order.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLEMENT_FAILED);
    }
}
