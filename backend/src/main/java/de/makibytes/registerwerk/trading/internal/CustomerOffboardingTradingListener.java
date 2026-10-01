package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link CustomerOffboardedEvent}: cancels every still-open trade listing where the
 * exiting entity is the seller (previously nothing did, so they could sit OPEN indefinitely after
 * the entity was CLOSED) and, since Phase 5 (5C-03), also the in-flight trades of the entity as
 * buyer or seller: PENDING is cancelled, AWAITING_SELLER_CONFIRMATION goes to the operator queue.
 */
@Component
class CustomerOffboardingTradingListener {

    private final TradeInvalidationService invalidation;

    CustomerOffboardingTradingListener(TradeInvalidationService invalidation) {
        this.invalidation = invalidation;
    }

    @ApplicationModuleListener
    void onCustomerOffboarded(CustomerOffboardedEvent event) {
        invalidation.onEntityOffboarded(event.entityId());
    }
}
