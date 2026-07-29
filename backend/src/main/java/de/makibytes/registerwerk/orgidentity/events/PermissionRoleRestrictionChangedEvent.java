package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record PermissionRoleRestrictionChangedEvent(UUID grantId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "PERMISSION_ROLE_RESTRICTION_CHANGED"; }
    public String subjectType() { return "PermissionGrant"; }
    public UUID   subjectId()   { return grantId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
