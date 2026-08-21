package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published whenever compensation does <b>not</b> resolve cleanly — no compensator registered for
 * the effect type, the compensator returned {@code Failed} (after retries are exhausted, in the
 * retry-job phase; immediately for now, since P5 ships only the synchronous path), or it returned
 * {@code Irreversible}. This is the audit trail for "fail-closed, not silently": every one of the
 * three failure shapes lands here rather than only as a log line, matching the plan's requirement
 * that a dashboard nobody watches is not fail-closed. A later phase additionally routes this into
 * a {@code dora.IctIncident} and a dedicated operator queue; for now, landing in {@code audit_event}
 * (queryable by any AUDIT/COMPLIANCE_OFFICER role) is the escalation surface.
 */
public record CompensationEscalatedEvent(
        UUID chainEffectId, String effectType, String entityType, UUID entityId,
        Reason reason, String detail, Instant occurredAt) implements AuditableEvent {

    /** Why compensation did not resolve — mirrors {@code chain_effect.status}'s terminal-failure
     *  values. */
    public enum Reason { NO_COMPENSATOR, FAILED, IRREVERSIBLE }

    public CompensationEscalatedEvent {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
    }

    public String eventType() { return "COMPENSATION_ESCALATED"; }
    public String subjectType() { return entityType; }
    public UUID subjectId() { return entityId; }
    public UUID actorId() { return null; }
    public String actorRole() { return null; }
    public Map<String, Object> payload() {
        return Map.of(
                "chainEffectId", chainEffectId.toString(),
                "effectType", effectType,
                "reason", reason.name(),
                "detail", detail == null ? "" : detail);
    }
}
