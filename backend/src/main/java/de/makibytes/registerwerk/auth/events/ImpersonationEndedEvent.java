package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** An impersonation session ended: EXITED, LOGOUT, EXPIRED, HANDOFF_REPLAY. */
public record ImpersonationEndedEvent(UUID sessionId, UUID actorId, UUID targetEntityId, String reason)
        implements AuditableEvent {
    public String eventType()   { return "ADMIN_IMPERSONATION_ENDED"; }
    public String subjectType() { return "ImpersonationSession"; }
    public UUID   subjectId()   { return sessionId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() {
        return Map.of("sessionId", sessionId.toString(), "targetEntityId", targetEntityId.toString(),
                "reason", reason);
    }
}
