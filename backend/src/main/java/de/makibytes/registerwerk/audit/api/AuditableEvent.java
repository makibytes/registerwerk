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

    /**
     * When this event records a correction/reversal of an earlier action, the
     * {@code audit_event.id} of the entry it reverses (e.g. a compensating force-burn
     * undoing a wrongful mint). Null for ordinary (non-correction) events.
     */
    default UUID reversesEventId() {
        return null;
    }

    /** Free-form grouping id linking audit entries that belong to one logical operation. */
    default UUID correlationId() {
        return null;
    }

    /**
     * The second approver validated by {@code StepUpEnforcementAspect}'s 4-eyes check for this
     * action, if any. Null for events that aren't dual-control-gated. Recorded into the audit
     * entry's payload by {@code AuditEvent.from(...)} — this identity must live in the
     * tamper-evident log itself, not only on the mutable domain entity (e.g. {@code
     * HolderBlock.dualControlApproverId}), so an examiner reconstructing "who was the second
     * approver" can do so from {@code audit_event} alone.
     */
    default UUID dualControlApproverId() {
        return null;
    }
}
