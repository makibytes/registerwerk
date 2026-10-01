package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hourly (5A-06: a daily run made "72h" up to 96h) timeout of reserved trades. Not itself
 * transactional: {@link TradeTimeoutProcessor} handles each row in its own transaction, so one bad
 * row cannot roll back the others and no row is written from a stale read.
 *
 * <ul>
 *   <li>PENDING without a declaration -> FAILED, quantity back to the listing (nothing was paid);</li>
 *   <li>declared payment never confirmed -> PAYMENT_UNRESOLVED, quantity stays reserved (5A-03).</li>
 * </ul>
 * Before the FIRST run of a process the overdue backlog is logged (SRE condition: the first run
 * after the rollout moves every already-overdue AWAITING trade into the operator queue).
 */
@Component
public class TradeTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(TradeTimeoutJob.class);

    private final TradeExecutionRepository executionRepository;
    private final TradeTimeoutProcessor processor;
    private final TradingProperties properties;
    private final TradeQueueService queueService;
    private final AtomicBoolean firstRun = new AtomicBoolean(true);

    public TradeTimeoutJob(TradeExecutionRepository executionRepository, TradeTimeoutProcessor processor,
                    TradingProperties properties, TradeQueueService queueService) {
        this.executionRepository = executionRepository;
        this.processor = processor;
        this.properties = properties;
        this.queueService = queueService;
    }

    @Scheduled(cron = "0 15 * * * *")
    @SchedulerLock(name = "tradePendingTimeout", lockAtMostFor = "PT30M")
    public void timeoutStuckTrades() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.getPendingTimeoutHours(), ChronoUnit.HOURS);
        if (firstRun.getAndSet(false)) {
            queueService.logBacklog(cutoff);
        }

        int pending = 0;
        for (UUID id : executionRepository.findIdsBySettlementStatusAndCreatedAtBefore(SettlementStatus.PENDING, cutoff)) {
            pending += runSafely(() -> processor.timeoutPending(id, cutoff), id);
        }
        int awaiting = 0;
        List<UUID> awaitingIds = executionRepository.findIdsBySettlementStatusAndPaymentDeclaredAtBefore(
                SettlementStatus.AWAITING_SELLER_CONFIRMATION, cutoff);
        for (UUID id : awaitingIds) {
            awaiting += runSafely(() -> processor.timeoutAwaiting(id, cutoff), id);
        }
        if (pending + awaiting > 0) {
            log.info("Timed out {} trade execution(s): {} failed awaiting payment declaration, {} moved to "
                    + "PAYMENT_UNRESOLVED awaiting seller confirmation.", pending + awaiting, pending, awaiting);
        }
        queueService.warnAgedUnresolved();
    }

    private int runSafely(java.util.function.BooleanSupplier action, UUID id) {
        try {
            return action.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException e) {
            // Includes an optimistic-lock conflict with a concurrent seller action: that action won.
            log.warn("Trade timeout skipped for execution {}: {}", id, e.toString());
            return 0;
        }
    }
}
