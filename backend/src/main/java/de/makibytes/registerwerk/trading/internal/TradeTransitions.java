package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradePaymentUnresolvedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePendingTimedOutEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The state changes of a reserved trade that end WITHOUT the register moving, in one place so the
 * service, the timeout job and the invalidation listeners cannot disagree (Phase 5, 5A-03).
 * Every method expects the caller to hold the execution's row lock ({@code findByIdForUpdate})
 * and to run inside a transaction; none of them opens its own.
 *
 * <p>The rule that matters: once a payment was declared, a trade is never auto-failed and its
 * units are never re-offered. {@link #markUnresolved} keeps the reservation and puts the trade in
 * the operator queue instead.
 */
@Component
class TradeTransitions {

    private static final Logger log = LoggerFactory.getLogger(TradeTransitions.class);

    private final TradeExecutionRepository executionRepository;
    private final TradeListingRepository listingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TradingProperties properties;

    TradeTransitions(TradeExecutionRepository executionRepository, TradeListingRepository listingRepository,
                     ApplicationEventPublisher eventPublisher, TradingProperties properties) {
        this.executionRepository = executionRepository;
        this.listingRepository = listingRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** AWAITING (or PENDING after a gate failure) -> PAYMENT_UNRESOLVED. Reservation is kept. */
    void markUnresolved(TradeExecution execution, String source, String reason, UUID actorId, String actorRole) {
        execution.setSettlementStatus(SettlementStatus.PAYMENT_UNRESOLVED);
        if (execution.getUnresolvedAt() == null) {
            execution.setUnresolvedAt(Instant.now());
        }
        // Fixed text per source: the detailed reason (gate output, seller free text) can describe the OTHER
        // party's compliance state and goes to the audit event only.
        execution.setUnresolvedReason(publicUnresolvedReason(source));
        executionRepository.save(execution);
        eventPublisher.publishEvent(new TradePaymentUnresolvedEvent(
                execution.getId(), actorId, actorRole, source, reason,
                execution.getBuyerEntityId(), execution.getSellerEntityId()));
        log.warn("Trade payment unresolved: id={} source={} reason={}", execution.getId(), source, reason);
    }

    static String publicUnresolvedReason(String source) {
        if (source == null) {
            return "The trade is on hold; the operator has been informed.";
        }
        return switch (source) {
            case "TIMEOUT" -> "TIMEOUT: The seller did not confirm the declared payment in time; the operator has been informed.";
            case "SELLER_DISPUTE" -> "SELLER_DISPUTE: The seller disputed the declared payment; the operator has been informed.";
            case "GATE_FAILED_AT_CONFIRM" ->
                    "GATE_FAILED_AT_CONFIRM: A settlement condition is no longer met; the operator has been informed.";
            default -> source + ": The trade was put on hold because its basis changed; the operator has been informed.";
        };
    }

    /** PENDING -> CANCELLED, quantity back to the listing. {@code buyerFault} starts the buyer's cool-down. */
    void cancelPending(TradeExecution execution, String reason, UUID actorId, String actorRole, boolean buyerFault) {
        execution.setSettlementStatus(SettlementStatus.CANCELLED);
        execution.setFailureReason(reason);
        if (buyerFault) {
            startCooldown(execution);
        }
        executionRepository.save(execution);
        restoreListingAvailability(execution);
        eventPublisher.publishEvent(new TradePendingCancelledEvent(execution.getId(), actorId, actorRole, reason));
    }

    /** PENDING that never got a payment declaration -> FAILED (nothing was paid), cool-down for the buyer. */
    void failPendingTimedOut(TradeExecution execution) {
        long hours = properties.getPendingTimeoutHours();
        execution.setSettlementStatus(SettlementStatus.FAILED);
        execution.setFailureReason("Timed out awaiting payment declaration after " + hours + "h");
        startCooldown(execution);
        executionRepository.save(execution);
        restoreListingAvailability(execution);
        eventPublisher.publishEvent(new TradePendingTimedOutEvent(execution.getId(), "SYSTEM", hours));
        log.warn("Trade execution timed out awaiting payment declaration: id={}", execution.getId());
    }

    /** Operator-resolved end without settlement (release / return of funds): FAILED + quantity restored. */
    void failAndRestore(TradeExecution execution, String reason) {
        execution.setSettlementStatus(SettlementStatus.FAILED);
        execution.setFailureReason(truncate(reason, 1000));
        executionRepository.save(execution);
        restoreListingAvailability(execution);
    }

    private void startCooldown(TradeExecution execution) {
        long hours = properties.getReservationCooldownHours();
        if (hours > 0) {
            execution.setBuyerCooldownUntil(Instant.now().plus(hours, ChronoUnit.HOURS));
        }
    }

    /** Releases a trade's reserved quantity back to its listing. A CANCELLED listing stays cancelled. */
    void restoreListingAvailability(TradeExecution execution) {
        listingRepository.findByIdForUpdate(execution.getListingId()).ifPresent(listing -> {
            listing.setQuantityAvailable(listing.getQuantityAvailable().add(execution.getExecutedQuantity()));
            if (listing.getStatus() == ListingStatus.FILLED || listing.getStatus() == ListingStatus.PARTIALLY_FILLED) {
                listing.setStatus(ListingStatus.OPEN);
            }
            listingRepository.save(listing);
        });
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
