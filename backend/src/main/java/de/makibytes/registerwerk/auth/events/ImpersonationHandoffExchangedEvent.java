package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** The one-time handoff code of an impersonation session was exchanged for a session cookie. */
public record ImpersonationHandoffExchangedEvent(UUID sessionId, UUID actorId, UUID targetEntityId, String mode)
        implements AuditableEvent {
    public String eventType()   { return "ADMIN_IMPERSONATION_HANDOFF_EXCHANGED"; }
    public String subjectType() { return "ImpersonationSession"; }
    public UUID   subjectId()   { return sessionId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() {
        return Map.of("sessionId", sessionId.toString(), "targetEntityId", targetEntityId.toString(), "mode", mode);
    }
}
