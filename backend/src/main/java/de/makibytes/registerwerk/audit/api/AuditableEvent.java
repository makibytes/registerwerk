package de.makibytes.registerwerk.audit.api;

import java.util.Map;
import java.util.UUID;

/**
 * Marker interface for all domain events that should be persisted to the audit log.
 * Any Spring event published by a module that implements this interface will be
 * automatically captured by the audit module's {@code AuditEventRecorder}.
 */
public interface AuditableEvent {

    String eventType();

    String subjectType();

    UUID subjectId();

    UUID actorId();

    String actorRole();

    Map<String, Object> payload();
}
