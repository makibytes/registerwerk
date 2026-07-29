package de.makibytes.registerwerk.shared.events;

import java.util.UUID;

/**
 * Published by {@code shared/web/GlobalExceptionHandler} whenever a mutating request is
 * blocked by an access-denial (including step-up/4-eyes rejections), an invalid state
 * transition, or a compliance-gate rejection ({@code ComplianceGateException}), so a
 * compliance officer reviewing the audit log can see that a wrongful action was attempted
 * and stopped — not just that it was SLF4J-logged.
 *
 * <p>Deliberately a plain record (not {@code audit.api.AuditableEvent}): the {@code shared}
 * module is the dependency-free foundation kernel and must not import the {@code audit}
 * module (that would create a module cycle, since {@code audit} already depends on
 * {@code shared}). The {@code audit} module listens for this event and converts it into an
 * {@code audit_event} row itself — the dependency runs one way, {@code audit -> shared}.
 */
public record RejectedActionEvent(
        String eventType, String path, String httpMethod, String reason, UUID actorId, String actorRole) {
}
