package de.makibytes.registerwerk.finality.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published exactly once per detected reorg — the first real consumer of a reorg's occurrence
 * outside {@code ReorgGuard}'s own log lines. Unlike {@code BlockFinalityChangedEvent}, this
 * genuinely is audit-worthy: a reorg reaching a previously-recorded block is a rare, compliance-
 * relevant event (potentially the trigger for downstream state correction in later phases of
 * this plan), not routine progression. System-generated — no operator actor, matching the
 * convention {@code IndexerStaleEvent} already uses (null actorId/actorRole).
 */
public record BlockRetractedEvent(
        UUID chainConfigId, long forkBlockNumber, String replacementBlockHash,
        int orphanedTransferCount, Instant occurredAt) implements AuditableEvent {

    public String eventType() { return "BLOCK_RETRACTED"; }
    public String subjectType() { return "ChainConfig"; }
    public UUID subjectId() { return chainConfigId; }
    public UUID actorId() { return null; }
    public String actorRole() { return null; }
    public Map<String, Object> payload() {
        return Map.of(
                "forkBlockNumber", forkBlockNumber,
                "replacementBlockHash", replacementBlockHash == null ? "" : replacementBlockHash,
                "orphanedTransferCount", orphanedTransferCount);
    }
}
