package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A DORA ICT incident was entered, by an operator or automatically as an unclassified draft (actorId null). */
public record IctIncidentCreatedEvent(UUID incidentId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ICT_INCIDENT_CREATED"; }
    public String subjectType() { return "IctIncident"; }
    public UUID   subjectId()   { return incidentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
