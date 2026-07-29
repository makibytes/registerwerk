package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Reacts to {@link CustomerOffboardedEvent}: cancels every still-open trade listing where the
 * exiting entity is the seller — previously nothing cancelled a leaving customer's open
 * listings, which could sit OPEN/PARTIALLY_FILLED indefinitely after the entity was CLOSED.
 */
@Component
class CustomerOffboardingTradingListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerOffboardingTradingListener.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final TradeListingRepository listingRepository;
    private final ApplicationEventPublisher eventPublisher;

    CustomerOffboardingTradingListener(TradeListingRepository listingRepository, ApplicationEventPublisher eventPublisher) {
        this.listingRepository = listingRepository;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onCustomerOffboarded(CustomerOffboardedEvent event) {
        List<TradeListing> openListings = listingRepository.findBySellerEntityIdOrderByCreatedAtDesc(event.entityId())
                .stream()
                .filter(l -> l.getStatus() == ListingStatus.OPEN || l.getStatus() == ListingStatus.PARTIALLY_FILLED)
                .toList();
        for (TradeListing listing : openListings) {
            listing.setStatus(ListingStatus.CANCELLED);
            listingRepository.save(listing);
            // Previously bypassed TradeListingCancelledEvent entirely — a
            // batch of listings could disappear from a customer's book with zero audit_event rows.
            eventPublisher.publishEvent(new TradeListingCancelledEvent(
                    listing.getId(), SYSTEM_ACTOR, "SYSTEM", listing.getSellerEntityId()));
        }
        if (!openListings.isEmpty()) {
            log.info("Cancelled {} open trade listing(s) for offboarded entity={}", openListings.size(), event.entityId());
        }
    }
}
