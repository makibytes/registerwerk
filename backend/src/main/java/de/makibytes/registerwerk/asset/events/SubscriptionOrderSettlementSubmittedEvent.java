package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** The mint of a paid subscription order was submitted through the durable outbox; the order is SETTLEMENT_PENDING
 *  until the mint is final and indexed (Wave 0b C7). */
public record SubscriptionOrderSettlementSubmittedEvent(UUID orderId, UUID actorId, String actorRole,
                                                        Map<String, Object> details) implements AuditableEvent {

    public String eventType()   { return "SUBSCRIPTION_ORDER_SETTLEMENT_SUBMITTED"; }
    public String subjectType() { return "SubscriptionOrder"; }
    public UUID   subjectId()   { return orderId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
