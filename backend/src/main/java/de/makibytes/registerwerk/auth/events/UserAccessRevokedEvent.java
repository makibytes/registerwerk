package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** An account was disabled through {@code AccountAccessPort} (access-review revocation). */
public record UserAccessRevokedEvent(UUID userId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "USER_ACCESS_REVOKED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
