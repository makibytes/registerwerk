package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** DORA Art. 19 authority notification (initial or final report) filed for an ICT incident. */
public record IctIncidentReportedEvent(UUID incidentId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ICT_INCIDENT_REPORTED_TO_AUTHORITY"; }
    public String subjectType() { return "IctIncident"; }
    public UUID   subjectId()   { return incidentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
