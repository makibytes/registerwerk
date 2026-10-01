package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.events.TradeRelatedPartyBlockedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Records the related-party alert in its own transaction (the refused buy rolls back). */
@Component
class RelatedPartyAlerts {

    private static final Logger log = LoggerFactory.getLogger(RelatedPartyAlerts.class);

    private final ApplicationEventPublisher publisher;

    RelatedPartyAlerts(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void blocked(UUID listingId, UUID actorId, UUID buyerEntityId, UUID sellerEntityId, List<String> reasons) {
        log.warn("SURVEILLANCE related-party trade blocked: listing={} buyer={} seller={} reasons={}",
                listingId, buyerEntityId, sellerEntityId, reasons);
        publisher.publishEvent(new TradeRelatedPartyBlockedEvent(
                listingId, actorId, "TRADER", buyerEntityId, sellerEntityId, reasons));
    }
}
