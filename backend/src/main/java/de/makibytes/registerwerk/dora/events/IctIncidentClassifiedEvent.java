package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A DORA ICT incident was (re)classified; payload carries before/after severity, reason, criteria and deadlines. */
public record IctIncidentClassifiedEvent(UUID incidentId, UUID actorId, String actorRole, UUID approverId,
                                         Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ICT_INCIDENT_CLASSIFIED"; }
    public String subjectType() { return "IctIncident"; }
    public UUID   subjectId()   { return incidentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    @Override public UUID dualControlApproverId() { return approverId; }
}
