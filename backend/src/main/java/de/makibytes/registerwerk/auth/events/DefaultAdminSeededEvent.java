package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** The bootstrap administrator was created because no REGISTRY_ADMIN existed. */
public record DefaultAdminSeededEvent(UUID userId, String email) implements AuditableEvent {
    public String eventType()   { return "DEFAULT_ADMIN_SEEDED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("email", email == null ? "" : email, "mustChangePassword", true, "roles", "REGISTRY_ADMIN");
    }
}
