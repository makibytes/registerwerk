package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** The holder of a LOCAL account changed their own password (never carries a secret). */
public record PasswordChangedEvent(UUID userId, String email, boolean wasForced) implements AuditableEvent {
    public String eventType()   { return "PASSWORD_CHANGED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public UUID   actorId()     { return userId; }
    public String actorRole()   { return "SELF"; }
    public Map<String, Object> payload() {
        return Map.of("email", email == null ? "" : email, "wasForced", wasForced);
    }
}
