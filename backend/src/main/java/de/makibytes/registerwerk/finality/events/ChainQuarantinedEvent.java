package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Auditable fail-closed signal for an unsafe or unverifiable canonical-chain divergence. */
public record ChainQuarantinedEvent(
        UUID chainConfigId,
        String reorgId,
        ReorgObservation.ReorgSeverity severity,
        QuarantineTrigger trigger,
        String triggerDetail,
        Instant occurredAt) implements AuditableEvent {

    public ChainQuarantinedEvent(UUID chainConfigId, String reorgId,
            ReorgObservation.ReorgSeverity severity, Instant occurredAt) {
        this(chainConfigId, reorgId, severity,
                severity == ReorgObservation.ReorgSeverity.FINALITY_VIOLATION
                        ? QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION
                        : QuarantineTrigger.UNRESOLVED_ANCESTRY,
                null, occurredAt);
    }

    @Override public String eventType() { return "CHAIN_QUARANTINED"; }
    @Override public String subjectType() { return "ChainConfig"; }
    @Override public UUID subjectId() { return chainConfigId; }
    @Override public UUID actorId() { return null; }
    @Override public String actorRole() { return null; }

    @Override
    public Map<String, Object> payload() {
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("reorgId", reorgId);
        payload.put("severity", severity.name());
        payload.put("trigger", trigger.name());
        if (triggerDetail != null) payload.put("triggerDetail", triggerDetail);
        return Map.copyOf(payload);
    }
}
