package de.makibytes.registerwerk.admin.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AdminImpersonationStartedEvent(UUID targetUserId, UUID actorId, String actorRole, Map<String, Object> details,
                                             UUID dualControlApproverId) implements AuditableEvent {
    public String eventType()   { return "ADMIN_IMPERSONATION_STARTED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return targetUserId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
    @Override public UUID dualControlApproverId() { return dualControlApproverId; }
}
