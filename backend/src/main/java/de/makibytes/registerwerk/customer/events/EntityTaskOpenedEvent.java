package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record EntityTaskOpenedEvent(UUID entityId, UUID actorId, UUID taskId, String kind, String refId, String detail)
        implements AuditableEvent {
    public String eventType()   { return "ENTITY_TASK_OPENED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public String actorRole()   { return actorId == null ? "SYSTEM" : null; }
    public Map<String, Object> payload() {
        return Map.of("taskId", taskId.toString(), "kind", kind, "refId", refId == null ? "" : refId,
                "detail", detail == null ? "" : detail);
    }
}
