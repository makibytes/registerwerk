package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Fired when the issuer/operator gave an allocation back. */
public record SubscriptionOrderReleasedEvent(UUID orderId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SUBSCRIPTION_ORDER_RELEASED"; }
    public String subjectType() { return "SubscriptionOrder"; }
    public UUID   subjectId()   { return orderId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
