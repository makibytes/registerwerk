package de.makibytes.registerwerk.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only projection of an audit log entry, safe for cross-module use.
 *
 * @param sequenceNo   this row's position in the hash chain
 * @param entryHashHex hex-encoded {@code entry_hash} — always present, needed to independently
 *                      recompute and check the chain link for this row
 * @param entrySigHex  hex-encoded Ed25519 signature over {@code entryHashHex}, or {@code null}
 *                      when no {@code SigningKeyProvider} is configured in this environment
 * @param reversesEventId audit entry this one corrects, if any
 * @param correlationId   grouping id of one logical operation, if any
 * @param canonVersion    canonical envelope version covered by {@code entryHashHex} (2 covers actor, role,
 *                        time, correlation and reversal; 1 is the legacy envelope)
 * @param prevHashHex     hex {@code prev_hash}, null for the genesis row
 * @param recordedAt      insert time (the event time is {@code occurredAt}); null for legacy rows
 */
public record AuditEventView(
        UUID id,
        String eventType,
        String subjectType,
        UUID subjectId,
        UUID actorId,
        String actorRole,
        Map<String, Object> payload,
        Instant occurredAt,
        Long sequenceNo,
        String entryHashHex,
        String entrySigHex,
        UUID reversesEventId,
        UUID correlationId,
        int canonVersion,
        String prevHashHex,
        Instant recordedAt
) {
    public AuditEventView(UUID id, String eventType, String subjectType, UUID subjectId, UUID actorId,
                          String actorRole, Map<String, Object> payload, Instant occurredAt, Long sequenceNo,
                          String entryHashHex, String entrySigHex) {
        this(id, eventType, subjectType, subjectId, actorId, actorRole, payload, occurredAt, sequenceNo,
                entryHashHex, entrySigHex, null, null, 1, null, null);
    }
}
