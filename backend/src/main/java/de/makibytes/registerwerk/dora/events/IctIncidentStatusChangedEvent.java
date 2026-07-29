package de.makibytes.registerwerk.dora.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** DORA Art. 17 ICT incident status transition (e.g. INVESTIGATING -> CONTAINED -> RESOLVED). */
public record IctIncidentStatusChangedEvent(UUID incidentId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ICT_INCIDENT_STATUS_CHANGED"; }
    public String subjectType() { return "IctIncident"; }
    public UUID   subjectId()   { return incidentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
