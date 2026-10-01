package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Surveillance alert (5A-06): a buy was refused because buyer and seller are linked (shared
 * beneficial owner / member / wallet). The refused request itself rolls back; this event is
 * published in its own transaction so the alert survives.
 */
public record TradeRelatedPartyBlockedEvent(UUID listingId, UUID actorId, String actorRole, UUID buyerEntityId,
                                            UUID sellerEntityId, List<String> reasons) implements AuditableEvent {
    public String eventType()   { return "TRADE_RELATED_PARTY_BLOCKED"; }
    public String subjectType() { return "TradeListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() {
        return Map.of("buyerEntityId", buyerEntityId.toString(), "sellerEntityId", sellerEntityId.toString(),
                "reasons", String.join(",", reasons));
    }
}
