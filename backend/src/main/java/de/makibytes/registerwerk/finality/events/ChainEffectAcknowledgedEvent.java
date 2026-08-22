package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published when an admin acknowledges a {@code chain_effect} that failed or was escalated as
 * irreversible — the action that lifts {@code FinalityGate}'s per-asset freeze (see
 * {@code FinalityGateImpl}'s javadoc). Always audit-worthy: this is exactly the kind of
 * break-glass action ("I have reviewed this and accept the risk of proceeding") that a regulated
 * register must be able to show an examiner, with the mandatory {@code reason} attached.
 */
public record ChainEffectAcknowledgedEvent(
        UUID chainEffectId, String effectType, String entityType, UUID entityId, UUID assetId,
        String reason, UUID actorId, String actorRole, Instant occurredAt) implements AuditableEvent {

    public String eventType() { return "CHAIN_EFFECT_ACKNOWLEDGED"; }
    public String subjectType() { return entityType; }
    public UUID subjectId() { return entityId; }
    public Map<String, Object> payload() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("chainEffectId", chainEffectId.toString());
        payload.put("effectType", effectType);
        payload.put("reason", reason);
        if (assetId != null) {
            payload.put("assetId", assetId.toString());
        }
        return payload;
    }
}
