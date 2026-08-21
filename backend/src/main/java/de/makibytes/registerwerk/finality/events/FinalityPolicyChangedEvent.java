package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published on every create/update/delete of a {@code finality_policy_assignment} or {@code
 * finality_policy_override} row. Always audit-worthy — unlike routine block-finality progression,
 * a policy change is a rare, deliberate admin action that changes what the (not-yet-built)
 * {@code FinalityGate} will require, so it is audited every time, not sampled or batched.
 */
public record FinalityPolicyChangedEvent(
        String changeType, UUID subjectId, UUID actorId, String actorRole,
        Map<String, Object> details, Instant occurredAt) implements AuditableEvent {

    public String eventType() { return "FINALITY_POLICY_CHANGED"; }
    public String subjectType() { return "FinalityPolicy"; }
    public UUID subjectId() { return subjectId; }
    public UUID actorId() { return actorId; }
    public String actorRole() { return actorRole; }
    public Map<String, Object> payload() {
        Map<String, Object> payload = new java.util.HashMap<>(details);
        payload.put("changeType", changeType);
        return payload;
    }
}
