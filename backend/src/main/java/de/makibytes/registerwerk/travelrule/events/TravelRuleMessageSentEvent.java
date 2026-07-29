package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * An outbound Travel Rule (TFR) message was recorded/dispatched for a transfer. Published as
 * {@code actorRole="SYSTEM"} (null {@code actorId}) — this fires automatically as part of a
 * transfer's compliance check, not as a direct user action.
 */
public record TravelRuleMessageSentEvent(UUID messageId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "TRAVEL_RULE_MESSAGE_SENT"; }
    public String subjectType() { return "TravelRuleMessage"; }
    public UUID   subjectId()   { return messageId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
