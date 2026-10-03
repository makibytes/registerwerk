package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** The mint of a paid subscription order definitively did not happen (reverted or replaced): the investor has paid and
 *  holds no units. The order is SETTLEMENT_FAILED and retryable; this is the operator alert (Wave 0b C7).
 *  System-attributed. */
public record SubscriptionOrderSettlementFailedEvent(UUID orderId, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "SUBSCRIPTION_ORDER_SETTLEMENT_FAILED"; }
    public String subjectType() { return "SubscriptionOrder"; }
    public UUID   subjectId()   { return orderId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
