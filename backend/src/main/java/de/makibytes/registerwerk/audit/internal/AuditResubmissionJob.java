package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.events.AuditDeadLetteredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodic retry of failed audit writes (6-12): resubmits incomplete audit publications older than
 * two minutes every minute (previously only at restart) and dead-letters poison publications after
 * {@code registerwerk.audit.max-attempts} attempts. Exposes the age of the oldest incomplete audit
 * publication as {@code registerwerk_audit_oldest_incomplete_seconds} (alert on a few minutes) and
 * the dead-letter backlog as {@code registerwerk_audit_dead_letter_count}.
 */
@Component
class AuditResubmissionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditResubmissionJob.class);
    static final String AUDIT_LISTENER_PREFIX = "de.makibytes.registerwerk.audit.";

    private final IncompleteEventPublications incomplete;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final Counter deadLettered;
    private volatile long gaugeCachedAt;
    private volatile double oldestCached;

    @Value("${registerwerk.audit.max-attempts:20}")
    private int maxAttempts;

    @Value("${registerwerk.audit.resubmit-older-than-seconds:120}")
    private long olderThanSeconds;

    AuditResubmissionJob(IncompleteEventPublications incomplete, JdbcTemplate jdbc,
                         ApplicationEventPublisher publisher, MeterRegistry registry) {
        this.incomplete = incomplete;
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.deadLettered = Counter.builder("registerwerk_audit_dead_lettered_total")
                .description("Audit publications moved to the dead-letter table").register(registry);
        Gauge.builder("registerwerk_audit_oldest_incomplete_seconds", this, AuditResubmissionJob::oldestIncompleteSeconds)
                .description("Age of the oldest incomplete audit event publication").register(registry);
        Gauge.builder("registerwerk_audit_dead_letter_count", this, j -> j.deadLetterCount())
                .description("Rows in audit_event_dead_letter").register(registry);
    }

    private double oldestIncompleteSeconds() {
        long now = System.currentTimeMillis();
        if (now - gaugeCachedAt > 15_000) {
            try {
                Double v = jdbc.queryForObject("""
                        SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(publication_date)))::float8, 0)
                        FROM event_publication WHERE completion_date IS NULL AND listener_id LIKE ?
                        """, Double.class, AUDIT_LISTENER_PREFIX + "%");
                oldestCached = v != null ? v : 0;
            } catch (RuntimeException e) {
                oldestCached = -1;
            }
            gaugeCachedAt = now;
        }
        return oldestCached;
    }

    private double deadLetterCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM audit_event_dead_letter", Long.class);
            return n != null ? n : 0;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @SchedulerLock(name = "auditResubmission", lockAtMostFor = "PT10M")
    @Scheduled(fixedDelayString = "${registerwerk.audit.resubmit-interval-ms:60000}", initialDelayString = "${registerwerk.audit.resubmit-interval-ms:60000}")
    void run() {
        runOnce();
    }

    void runOnce() {
        moveToDeadLetter();
        java.time.Instant cutoff = java.time.Instant.now().minus(Duration.ofSeconds(olderThanSeconds));
        incomplete.resubmitIncompletePublications(p -> isAuditEvent(p.getEvent())
                && p.getPublicationDate().isBefore(cutoff));
    }

    private static boolean isAuditEvent(Object event) {
        return event instanceof AuditRecord
                || event instanceof de.makibytes.registerwerk.shared.events.RejectedActionEvent;
    }

    /** Moves audit publications that exhausted their attempts out of the retry loop. */
    @Transactional
    void moveToDeadLetter() {
        List<Map<String, Object>> poison = jdbc.queryForList("""
                SELECT id, listener_id, event_type, serialized_event, publication_date, completion_attempts
                FROM event_publication
                WHERE completion_date IS NULL AND listener_id LIKE ? AND COALESCE(completion_attempts, 0) >= ?
                """, AUDIT_LISTENER_PREFIX + "%", maxAttempts);
        for (Map<String, Object> row : poison) {
            UUID id = (UUID) row.get("id");
            int attempts = ((Number) row.get("completion_attempts")).intValue();
            jdbc.update("""
                    INSERT INTO audit_event_dead_letter (publication_id, listener_id, event_type, serialized_event,
                                                         publication_date, attempts)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, row.get("listener_id"), row.get("event_type"), row.get("serialized_event"),
                    row.get("publication_date"), attempts);
            jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
            deadLettered.increment();
            log.error("Audit publication {} dead-lettered after {} attempts (type {}); manual follow-up required",
                    id, attempts, row.get("event_type"));
            publisher.publishEvent(new AuditDeadLetteredEvent(id, String.valueOf(row.get("listener_id")),
                    String.valueOf(row.get("event_type")), attempts));
        }
    }
}
