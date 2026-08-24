package de.makibytes.registerwerk.admin.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * An operator issued a Temporary Access Pass so a customer who lost their authenticator can sign
 * in once and re-register.
 *
 * <p><strong>{@code details} must never contain the pass itself.</strong> A TAP is a bearer
 * credential that fully authenticates as the target user, and {@code audit_event} is a
 * long-retention, widely readable table — putting it there would turn the audit trail into a
 * credential store. Record the Graph method id, lifetime and single-use flag instead, which is
 * what an auditor actually needs. {@code EntraSupportServiceTest} asserts this.
 */
public record OperatorEntraTapIssuedEvent(UUID targetUserId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "OPERATOR_ENTRA_TAP_ISSUED"; }
    public String subjectType() { return "AppUser"; }
    public UUID   subjectId()   { return targetUserId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
