package de.makibytes.registerwerk.marketplace.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Audit event covering every dApp-listing lifecycle transition (submit, review,
 * approve, reject, publish, deprecate, delist). {@code action} names the transition.
 */
public record DappListingEvent(String action, UUID listingId, UUID actorId, String actorRole,
                               Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "DAPP_LISTING_" + action; }
    public String subjectType() { return "DappListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
