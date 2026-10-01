package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record EntityTaskCompletedEvent(UUID entityId, UUID actorId, String actorRole, UUID taskId, String kind,
                                       String note) implements AuditableEvent {
    public String eventType()   { return "ENTITY_TASK_COMPLETED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        return Map.of("taskId", taskId.toString(), "kind", kind, "note", note == null ? "" : note);
    }
}
