package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Fired when an allocation was not paid within its window and its capacity was released. */
public record SubscriptionOrderLapsedEvent(UUID orderId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SUBSCRIPTION_ORDER_LAPSED"; }
    public String subjectType() { return "SubscriptionOrder"; }
    public UUID   subjectId()   { return orderId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
