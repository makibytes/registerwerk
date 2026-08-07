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
        String entrySigHex
) {}
