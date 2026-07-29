package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record PermissionDefinitionCreatedEvent(UUID definitionId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "PERMISSION_DEFINITION_CREATED"; }
    public String subjectType() { return "PermissionDefinition"; }
    public UUID   subjectId()   { return definitionId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
