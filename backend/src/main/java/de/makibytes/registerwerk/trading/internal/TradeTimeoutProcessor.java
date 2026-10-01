package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * One timed-out trade per transaction (Phase 5, 5A-03). Each row is re-loaded under
 * {@code findByIdForUpdate} and its status re-checked, so a seller confirm that committed between
 * the job's id scan and this transaction wins (the row is no longer AWAITING) instead of being
 * overwritten by a stale whole-entity save - the lost update the previous single-transaction job
 * allowed. {@code TradeExecution.version} is the second line of defence.
 */
@Component
public class TradeTimeoutProcessor {

    private static final String SYSTEM_ROLE = "SYSTEM";

    private final TradeExecutionRepository executionRepository;
    private final TradeTransitions transitions;
    private final TradingProperties properties;

    public TradeTimeoutProcessor(TradeExecutionRepository executionRepository, TradeTransitions transitions,
                          TradingProperties properties) {
        this.executionRepository = executionRepository;
        this.transitions = transitions;
        this.properties = properties;
    }

    /** PENDING without a payment declaration -> FAILED. Returns true if the row was changed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean timeoutPending(UUID executionId, Instant cutoff) {
        TradeExecution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution == null || execution.getSettlementStatus() != SettlementStatus.PENDING
                || !execution.getCreatedAt().isBefore(cutoff)) {
            return false;
        }
        transitions.failPendingTimedOut(execution);
        return true;
    }

    /** Declared payment the seller never confirmed -> PAYMENT_UNRESOLVED (never FAILED). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean timeoutAwaiting(UUID executionId, Instant cutoff) {
        TradeExecution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution == null || execution.getSettlementStatus() != SettlementStatus.AWAITING_SELLER_CONFIRMATION
                || execution.getPaymentDeclaredAt() == null || !execution.getPaymentDeclaredAt().isBefore(cutoff)) {
            return false;
        }
        transitions.markUnresolved(execution, "TIMEOUT",
                "Seller did not confirm or dispute the declared payment within "
                        + properties.getPendingTimeoutHours() + "h", null, SYSTEM_ROLE);
        return true;
    }
}
