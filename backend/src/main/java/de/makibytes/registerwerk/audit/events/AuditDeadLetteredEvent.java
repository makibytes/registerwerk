package de.makibytes.registerwerk.audit.events;

import java.util.UUID;

/**
 * Published when a poison audit publication was moved to {@code audit_event_dead_letter} after
 * repeated failures. Hook for incident handling (DORA draft incident, notification); deliberately
 * not an {@code AuditableEvent} so a failing audit write cannot recurse into the audit chain.
 */
public record AuditDeadLetteredEvent(UUID publicationId, String listenerId, String eventType, int attempts) {
}
