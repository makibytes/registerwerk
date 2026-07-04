package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** DSGVO Art. 17 erasure request filed by a data subject. */
public record DsarErasureRequestedEvent(UUID entityId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "DSAR_ERASURE_REQUESTED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
