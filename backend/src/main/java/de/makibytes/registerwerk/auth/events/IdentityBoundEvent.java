package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** An IdP identity (Entra oid / OIDC sub) was bound to an existing account for the first time. */
public record IdentityBoundEvent(UUID userId, String provider, String previousId, String newId, String tenantId, String matchedBy)
        implements AuditableEvent {
    public String eventType()   { return "IDENTITY_BOUND"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of(
                "provider", provider,
                "previousId", previousId == null ? "" : previousId,
                "newId", newId == null ? "" : newId,
                "tenantId", tenantId == null ? "" : tenantId,
                "matchedBy", matchedBy);
    }
}
