package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class AuditEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRecorder.class);

    private final AuditEventRepository repository;
    private final AuditChainAppender chainAppender;

    AuditEventRecorder(AuditEventRepository repository, AuditChainAppender chainAppender) {
        this.repository = repository;
        this.chainAppender = chainAppender;
    }

    /**
     * {@code @ApplicationModuleListener} = {@code @Async} + {@code @TransactionalEventListener}
     * (default phase {@code AFTER_COMMIT}) + {@code @Transactional(propagation = REQUIRES_NEW)}.
     * By the time this method runs, the originating business transaction has ALREADY
     * committed — a failed audit write here cannot roll back an action that already
     * happened. What "no try/catch" buys instead: an exception here fails this
     * listener's own REQUIRES_NEW transaction, which Spring Modulith's JDBC event
     * publication registry (the {@code event_publication} outbox table) records as
     * incomplete; {@code republish-outstanding-events-on-restart=true} (application.yml)
     * retries it at least once more. Regulator-grade actions therefore get an
     * at-least-once, eventually-consistent audit trail rather than a synchronous
     * audit-or-rollback guarantee — silently swallowing the exception here would drop
     * that retry and the eWpRV §6 record with it.
     *
     * <p>Previously also read {@code SecurityContextHolder} here to enrich the payload with a
     * human-readable {@code actorName} — removed: this listener runs on a
     * separate thread after the originating request thread returns ({@code @Async}, no
     * security-context-propagating executor is configured anywhere in this codebase), so
     * {@code SecurityContextHolder.getContext().getAuthentication()} was always null here in
     * production; {@code actorId}/{@code actorRole} are captured synchronously by the caller
     * at event-construction time and are unaffected.
     */
    @ApplicationModuleListener
    void on(AuditableEvent event) {
        AuditEvent ae = AuditEvent.from(event);
        chainAppender.append(ae);
        repository.save(ae);
        log.debug("Recorded audit event: type={}, subject={}/{}, seq={}",
                event.eventType(), event.subjectType(), event.subjectId(), ae.getSequenceNo());
    }
}
