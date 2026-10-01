package de.makibytes.registerwerk.auth.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A token matched an account by e-mail but the account is bound to a different IdP identity, or
 * the token does not meet the linking conditions. The token resolves to no account; an operator
 * must re-bind the identity deliberately (step-up + second approver).
 */
public record IdentityRebindRefusedEvent(UUID userId, String provider, String boundId, String presentedId,
                                         String reason) implements AuditableEvent {
    public String eventType()   { return "IDENTITY_REBIND_REFUSED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return userId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of(
                "provider", provider,
                "boundId", boundId == null ? "" : boundId,
                "presentedId", presentedId == null ? "" : presentedId,
                "reason", reason);
    }
}
