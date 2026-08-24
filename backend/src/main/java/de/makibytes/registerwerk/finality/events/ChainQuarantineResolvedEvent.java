package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Audited, explicit operator decision that re-enables ingestion and chain-scoped operations. */
public record ChainQuarantineResolvedEvent(
        UUID chainConfigId,
        String reorgId,
        String reason,
        UUID actorId,
        String actorRole,
        Instant occurredAt) implements AuditableEvent {

    @Override public String eventType() { return "CHAIN_QUARANTINE_RESOLVED"; }
    @Override public String subjectType() { return "ChainConfig"; }
    @Override public UUID subjectId() { return chainConfigId; }
    @Override public Map<String, Object> payload() {
        return Map.of("reorgId", reorgId, "reason", reason);
    }
}
