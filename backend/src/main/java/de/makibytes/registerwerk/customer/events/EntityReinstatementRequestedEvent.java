package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A CLOSED/DISSOLVED entity entered PENDING_REACTIVATION (T6-12). The payload carries the
 * mandatory reason and legal reference, the previous status and the second approver (via the
 * dual-control audit event). On-chain state is deliberately not touched by this event.
 */
public record EntityReinstatementRequestedEvent(UUID entityId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ENTITY_REINSTATEMENT_REQUESTED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
