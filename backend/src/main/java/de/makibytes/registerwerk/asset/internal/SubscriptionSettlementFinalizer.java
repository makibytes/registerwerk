package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettledEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettlementFailedEvent;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Settles a subscription order on the chain OUTCOME, not on submission (Wave 0b C7).
 *
 * <p>An order is {@code SETTLEMENT_PENDING} while its mint (submitted through the durable outbox) is in flight. It
 * becomes {@code SETTLED} only when BOTH signals are in: the mint transaction is {@code SUCCESS} (the chain's
 * configured finality model has been cleared, not merely one mined receipt) AND the indexer has recorded the MINT
 * transfer to the investor's wallet for the allocated amount as {@code FINALIZED}. A mint that definitively did not
 * happen ({@code FAILED} = reverted, {@code REPLACED} = its nonce was consumed by another transaction) moves the order
 * to {@code SETTLEMENT_FAILED}: audited, alerting, and retryable by settling again. {@code TIMEOUT} is NOT a failure -
 * the transaction may still be mined - so the order waits; a retry on TIMEOUT could mint twice.
 *
 * <p>Two triggers, one idempotent {@link #reconcile}: the {@link BlockchainTxStatusEvent} the transaction poller
 * publishes on completion (low latency) and a periodic sweep (the indexed transfer can arrive after the receipt, and
 * events can be missed).
 */
@Component
class SubscriptionSettlementFinalizer {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSettlementFinalizer.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final SubscriptionOrderRepository orders;
    private final BlockchainApi blockchain;
    private final IndexedTransferLookup indexed;
    private final ApplicationEventPublisher events;
    private final IsolatedTransactionExecutor isolated;

    SubscriptionSettlementFinalizer(SubscriptionOrderRepository orders, BlockchainApi blockchain,
                                    IndexedTransferLookup indexed, ApplicationEventPublisher events,
                                    IsolatedTransactionExecutor isolated, MeterRegistry meterRegistry) {
        this.orders = orders;
        this.blockchain = blockchain;
        this.indexed = indexed;
        this.events = events;
        this.isolated = isolated;
        Gauge.builder("registerwerk_subscription_settlement_failed", orders,
                        r -> r.countByStatus(SubscriptionOrder.Status.SETTLEMENT_FAILED))
                .description("Paid subscription orders whose mint definitively failed (SETTLEMENT_FAILED): the investor has paid and holds no units")
                .register(meterRegistry);
        Gauge.builder("registerwerk_subscription_settlement_pending", orders,
                        r -> r.countByStatus(SubscriptionOrder.Status.SETTLEMENT_PENDING))
                .description("Subscription orders whose mint is submitted but not final yet")
                .register(meterRegistry);
    }

    /** Low-latency trigger: the completion of a transaction that some order is waiting for. */
    @ApplicationModuleListener
    void onTransactionStatus(BlockchainTxStatusEvent event) {
        Object hash = event.details() == null ? null : event.details().get("txHash");
        if (hash == null) {
            return;
        }
        blockchain.findByTxHash(String.valueOf(hash))
                .flatMap(tx -> orders.findBySettlementTxId(tx.id()))
                .ifPresent(order -> reconcile(order.getId()));
    }

    /** Safety net: the indexed transfer arrives after the receipt, and events can be missed. */
    @SchedulerLock(name = "subscriptionSettlementFinalizer", lockAtMostFor = "PT4M")
    @Scheduled(fixedDelayString = "${registerwerk.subscription.settlement-sweep-ms:30000}",
            initialDelayString = "${registerwerk.subscription.settlement-sweep-initial-ms:60000}")
    public void sweep() {
        for (SubscriptionOrder pending : orders.findByStatusOrderBySubmittedAtAsc(SubscriptionOrder.Status.SETTLEMENT_PENDING)) {
            UUID orderId = pending.getId();
            try {
                isolated.run(() -> reconcile(orderId));
            } catch (Exception e) {
                log.error("Subscription settlement reconciliation failed for order {}: {}", orderId, e.getMessage(), e);
            }
        }
    }

    /** Idempotent: re-reads the order under a row lock and acts only on SETTLEMENT_PENDING. */
    void reconcile(UUID orderId) {
        SubscriptionOrder order = orders.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != SubscriptionOrder.Status.SETTLEMENT_PENDING
                || order.getSettlementTxId() == null) {
            return;
        }
        Optional<BlockchainTransactionView> found = blockchain.findTransaction(order.getSettlementTxId());
        if (found.isEmpty()) {
            log.warn("Subscription order {} waits for mint transaction {} which is not tracked", orderId,
                    order.getSettlementTxId());
            return;
        }
        BlockchainTransactionView tx = found.get();
        switch (tx.status()) {
            case "SUCCESS" -> {
                if (mintIsIndexed(order, tx)) {
                    markSettled(order, tx);
                } else {
                    log.info("Subscription order {}: mint {} succeeded, its MINT transfer is not indexed as FINALIZED yet",
                            orderId, tx.txHash());
                }
            }
            case "FAILED", "REPLACED" -> markFailed(order, tx);
            default -> log.debug("Subscription order {}: mint {} is {} - still in flight", orderId, tx.txHash(), tx.status());
        }
    }

    private boolean mintIsIndexed(SubscriptionOrder order, BlockchainTransactionView tx) {
        String hash = tx.minedTxHash() != null ? tx.minedTxHash() : tx.txHash();
        BigDecimal expected = order.getAllocatedAmount();
        return indexed.finalizedTransfers(hash).stream().anyMatch(t -> t.mint()
                && t.toAddress() != null && t.toAddress().equalsIgnoreCase(order.getWalletAddress())
                && t.amount() != null && expected != null && t.amount().compareTo(expected) == 0);
    }

    private void markSettled(SubscriptionOrder order, BlockchainTransactionView tx) {
        order.setStatus(SubscriptionOrder.Status.SETTLED);
        order.setSettledAt(Instant.now());
        order.setSettlementFailedAt(null);
        order.setSettlementFailureReason(null);
        orders.save(order);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assetId", order.getAssetId());
        details.put("holderId", order.getResultingHolderId());
        details.put("allocatedAmount", order.getAllocatedAmount());
        details.put("investorEntityId", order.getInvestorEntityId());
        details.put("onchainMint", true);
        details.put("mintTxId", order.getSettlementTxId());
        details.put("mintTxHash", tx.txHash());
        events.publishEvent(new SubscriptionOrderSettledEvent(order.getId(), SYSTEM_ACTOR, "SYSTEM", details));
        log.info("Subscription order settled on the chain outcome: id={} mint={}", order.getId(), tx.txHash());
    }

    private void markFailed(SubscriptionOrder order, BlockchainTransactionView tx) {
        String reason = "mint " + tx.txHash() + " " + tx.status() + (tx.errorMessage() != null ? ": " + tx.errorMessage() : "");
        order.setStatus(SubscriptionOrder.Status.SETTLEMENT_FAILED);
        order.setSettlementFailedAt(Instant.now());
        order.setSettlementFailureReason(reason);
        orders.save(order);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assetId", order.getAssetId());
        details.put("investorEntityId", order.getInvestorEntityId());
        details.put("allocatedAmount", order.getAllocatedAmount());
        details.put("mintTxId", order.getSettlementTxId());
        details.put("mintTxHash", tx.txHash());
        details.put("txStatus", tx.status());
        details.put("reason", reason);
        events.publishEvent(new SubscriptionOrderSettlementFailedEvent(order.getId(), details));
        log.error("Subscription order {} SETTLEMENT_FAILED: the investor has paid and holds no units - {} "
                + "(settle again to retry, or release the allocation and refund)", order.getId(), reason);
    }
}
