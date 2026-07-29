package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** {@code sellerEntityId} added so a bulk cancellation (e.g. customer
 *  offboarding) is attributable without joining back to the listing row. */
public record TradeListingCancelledEvent(UUID listingId, UUID actorId, String actorRole, UUID sellerEntityId) implements AuditableEvent {
    public String eventType()   { return "TRADE_LISTING_CANCELLED"; }
    public String subjectType() { return "TradeListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() {
        return Map.of("sellerEntityId", sellerEntityId != null ? sellerEntityId.toString() : "");
    }
}
