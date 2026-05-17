package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TradeListingCancelledEvent(UUID listingId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "TRADE_LISTING_CANCELLED"; }
    public String subjectType() { return "TradeListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() { return Map.of(); }
}
