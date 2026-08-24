package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published once a {@code ChainEffectCompensator} successfully undoes an effect. The first real
 * user of {@link AuditableEvent#reversesEventId()} — {@code sourceAuditEventId} is the
 * {@code audit_event.id} the original state change was recorded under (the {@code chain_effect}
 * row's {@code audit_event_id}), so an examiner can trace "this action was later reversed" from
 * the audit log alone, without needing to know the {@code chain_effect} table exists.
 */
public record ChainEffectCompensatedEvent(
        UUID chainEffectId, String effectType, String entityType, UUID entityId,
        UUID sourceAuditEventId, String detail, Instant occurredAt) implements AuditableEvent {

    public String eventType() { return "CHAIN_EFFECT_COMPENSATED"; }
    public String subjectType() { return entityType; }
    public UUID subjectId() { return entityId; }
    public UUID actorId() { return null; }
    public String actorRole() { return null; }
    public UUID reversesEventId() { return sourceAuditEventId; }
    public Map<String, Object> payload() {
        return Map.of(
                "chainEffectId", chainEffectId.toString(),
                "effectType", effectType,
                "detail", detail == null ? "" : detail);
    }
}
