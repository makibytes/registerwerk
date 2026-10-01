package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists audit entries. {@code @ApplicationModuleListener} = async + after-commit + REQUIRES_NEW;
 * a failure leaves the event publication incomplete, which {@code AuditResubmissionJob} retries
 * periodically (and dead-letters after repeated failures). Time and security context come from
 * {@link AuditRecord}, fixed at publish by {@link AuditEventCapture}.
 */
@Component
class AuditEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRecorder.class);

    private final AuditEventRepository repository;
    private final AuditChainAppender chainAppender;
    private final AuditEventCapture capture;

    /** Cut-over switch (6-12): drains publications created before the capture listener existed. */
    @Value("${registerwerk.audit.legacy-listener:true}")
    private boolean legacyListener;

    AuditEventRecorder(AuditEventRepository repository, AuditChainAppender chainAppender, AuditEventCapture capture) {
        this.repository = repository;
        this.chainAppender = chainAppender;
        this.capture = capture;
    }

    @ApplicationModuleListener
    void on(AuditRecord record) {
        AuditEvent ae = AuditEvent.fromRecord(record);
        chainAppender.append(ae);
        repository.save(ae);
        log.debug("Recorded audit event: type={}, subject={}/{}, seq={}",
                ae.getEventType(), ae.getSubjectType(), ae.getSubjectId(), ae.getSequenceNo());
    }

    /**
     * Legacy listener signature, retained ONLY so publications written before the cut-over (their
     * listener id is this method) still drain. Events captured in this JVM are skipped (recorded via
     * {@link #on(AuditRecord)}); a replayed legacy event has no event time, so its processing time
     * is used and flagged in the payload. With {@code registerwerk.audit.legacy-listener=false}
     * a replayed legacy event is refused (stays incomplete and visible) instead of recorded.
     */
    @ApplicationModuleListener
    void on(AuditableEvent event) {
        if (capture.wasCaptured(event)) {
            return;
        }
        if (!legacyListener) {
            throw new IllegalStateException("Legacy audit listener disabled; refusing replayed event "
                    + event.eventType());
        }
        AuditEvent ae = AuditEvent.from(event);
        Map<String, Object> payload = ae.getPayload() != null ? new LinkedHashMap<>(ae.getPayload()) : new LinkedHashMap<>();
        payload.put("_occurredAtSource", "PROCESSING_TIME");
        ae.setPayload(payload);
        ae.setCanonVersion((short) 2);
        ae.setRecordedAt(ae.getOccurredAt());
        chainAppender.append(ae);
        repository.save(ae);
        log.warn("Recorded legacy-replayed audit event type={} seq={}", ae.getEventType(), ae.getSequenceNo());
    }
}
