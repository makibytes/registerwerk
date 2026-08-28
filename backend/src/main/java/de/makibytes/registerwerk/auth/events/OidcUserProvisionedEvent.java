package de.makibytes.registerwerk.auth.events;

import java.util.Map;
import java.util.UUID;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

/**
 * Emitted when a first-seen OIDC/Entra principal is provisioned as a disabled account awaiting
 * operator approval.
 */
public record OidcUserProvisionedEvent(UUID userId, String email, String provider) implements AuditableEvent {
    @Override
    public String eventType() {
        return "OIDC_USER_PROVISIONED";
    }

    @Override
    public String subjectType() {
        return "AppUser";
    }

    @Override
    public UUID subjectId() {
        return userId;
    }

    @Override
    public UUID actorId() {
        return null;
    }

    @Override
    public String actorRole() {
        return "SYSTEM";
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
                "email", email == null ? "" : email,
                "provider", provider == null ? "" : provider);
    }
}
