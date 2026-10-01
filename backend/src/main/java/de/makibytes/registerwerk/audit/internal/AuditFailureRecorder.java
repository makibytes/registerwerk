package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.shared.events.RejectedActionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for {@link RejectedActionEvent} (published by {@code shared/web/GlobalExceptionHandler}
 * for blocked mutating requests — access-denied, step-up/4-eyes rejection, invalid state
 * transition) and writes a {@code REJECTED_ACTION}-style row to {@code audit_event}.
 *
 * <p>{@code subject_id} has no natural domain entity to point to at this generic
 * HTTP-exception-handler layer, so it is a deterministic UUID derived from
 * {@code "<method> <path>"} — rejections on the same endpoint share a subject id, so
 * {@code findBySubjectType("HttpRequest")} groups naturally by endpoint.
 *
 * <p>Like {@link AuditEventRecorder}, {@code @ApplicationModuleListener} runs this
 * asynchronously in its own {@code REQUIRES_NEW} transaction after the originating
 * (already-failed) request completes, via the same JDBC event-publication outbox.
 */
@Component
class AuditFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditFailureRecorder.class);

    private final AuditEventRepository repository;
    private final AuditChainAppender chainAppender;
    private final AuditEventCapture capture;

    AuditFailureRecorder(AuditEventRepository repository, AuditChainAppender chainAppender,
                         AuditEventCapture capture) {
        this.repository = repository;
        this.chainAppender = chainAppender;
        this.capture = capture;
    }

    /** Legacy listener signature: drains rejected-action publications created before the cut-over. */
    @ApplicationModuleListener
    void on(RejectedActionEvent event) {
        if (capture.wasCaptured(event)) {
            return;
        }
        AuditEvent ae = new AuditEvent();
        ae.setEventType(event.eventType());
        ae.setSubjectType("HttpRequest");
        ae.setSubjectId(UUID.nameUUIDFromBytes(
                (event.httpMethod() + " " + event.path()).getBytes(StandardCharsets.UTF_8)));
        ae.setActorId(event.actorId());
        ae.setActorRole(event.actorRole());
        ae.setPayload(Map.of("path", event.path(), "method", event.httpMethod(),
                "reason", event.reason() != null ? event.reason() : "",
                "_occurredAtSource", "PROCESSING_TIME"));
        ae.setCanonVersion((short) 2);
        ae.setRecordedAt(ae.getOccurredAt());
        chainAppender.append(ae);
        repository.save(ae);
    }
}
