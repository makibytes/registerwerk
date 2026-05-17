package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TradeListingCreatedEvent(UUID listingId, UUID actorId, String actorRole, UUID assetId) implements AuditableEvent {
    public String eventType()   { return "TRADE_LISTING_CREATED"; }
    public String subjectType() { return "TradeListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() { return assetId != null ? Map.of("assetId", assetId.toString()) : Map.of(); }
}
