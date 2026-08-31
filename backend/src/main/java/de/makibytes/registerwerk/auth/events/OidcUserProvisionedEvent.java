package de.makibytes.registerwerk.auth.events;

import java.util.Map;
import java.util.UUID;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

/**
 * Emitted when a first-seen OIDC/Entra principal is provisioned as a disabled account awaiting
 * operator approval.
 */
public record OidcUserProvisionedEvent(UUID userId, String email, String provider) implements AuditableEvent {
    public String eventType()   { return "OIDC_USER_PROVISIONED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }

    public Map<String, Object> payload() {
        return Map.of(
                "email", email == null ? "" : email,
                "provider", provider == null ? "" : provider);
    }
}
