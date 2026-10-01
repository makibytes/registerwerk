package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * An account was created with, or granted, REGISTRY_ADMIN or COMPLIANCE_OFFICER. Audited, and
 * raised to every REGISTRY_ADMIN by the notification module.
 */
public record PrivilegedAccountChangedEvent(UUID userId, UUID actorId, String actorRole, String email,
                                            String change, java.util.Set<String> roles, boolean bootstrap)
        implements AuditableEvent {
    public String eventType()   { return "PRIVILEGED_ACCOUNT_CHANGED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public Map<String, Object> payload() {
        return Map.of("email", email == null ? "" : email, "change", change,
                "roles", java.util.List.copyOf(new java.util.TreeSet<>(roles)), "bootstrap", bootstrap);
    }
}
