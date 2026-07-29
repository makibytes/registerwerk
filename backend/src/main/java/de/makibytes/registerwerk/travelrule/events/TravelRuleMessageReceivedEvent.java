package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * An inbound Travel Rule (TFR) message was received from a counterparty VASP. Published as
 * {@code actorRole="SYSTEM"} (null {@code actorId}) — received via the unauthenticated public
 * inbox endpoint, not a Registerwerk user action.
 */
public record TravelRuleMessageReceivedEvent(UUID messageId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "TRAVEL_RULE_MESSAGE_RECEIVED"; }
    public String subjectType() { return "TravelRuleMessage"; }
    public UUID   subjectId()   { return messageId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
