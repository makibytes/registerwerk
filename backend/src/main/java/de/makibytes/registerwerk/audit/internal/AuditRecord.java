package de.makibytes.registerwerk.audit.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An audit entry with its event time and security context fixed at publish time (6-12). Published
 * synchronously by {@link AuditEventCapture} so the time and context are persisted with the event
 * publication and survive async delays and restart replays.
 */
public record AuditRecord(
        String eventType,
        String subjectType,
        UUID subjectId,
        UUID actorId,
        String actorRole,
        Map<String, Object> payload,
        Instant occurredAt,
        UUID reversesEventId,
        UUID correlationId,
        UUID recordId) {

    /** Stable identity for idempotent append (7A-05); null on publications written before it existed. */
    public AuditRecord(String eventType, String subjectType, UUID subjectId, UUID actorId, String actorRole,
                       Map<String, Object> payload, Instant occurredAt, UUID reversesEventId, UUID correlationId) {
        this(eventType, subjectType, subjectId, actorId, actorRole, payload, occurredAt, reversesEventId,
                correlationId, null);
    }
}
