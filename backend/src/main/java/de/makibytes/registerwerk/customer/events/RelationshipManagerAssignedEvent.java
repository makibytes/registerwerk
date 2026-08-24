package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record RelationshipManagerAssignedEvent(UUID entityId, UUID actorId, String actorRole, UUID relationshipManagerId)
        implements AuditableEvent {
    public String eventType()   { return "RELATIONSHIP_MANAGER_ASSIGNED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        return Map.of("relationshipManagerId", relationshipManagerId != null ? relationshipManagerId.toString() : "");
    }
}
