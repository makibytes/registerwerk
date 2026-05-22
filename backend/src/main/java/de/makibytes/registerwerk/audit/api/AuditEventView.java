package de.makibytes.registerwerk.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read-only projection of an audit log entry, safe for cross-module use. */
public record AuditEventView(
        UUID id,
        String eventType,
        String subjectType,
        UUID subjectId,
        UUID actorId,
        String actorRole,
        Map<String, Object> payload,
        Instant occurredAt
) {}
