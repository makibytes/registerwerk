package de.makibytes.registerwerk.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refreshes two operational gauges that were previously visible only via log lines, not metrics
 * (Phase 4):
 *
 * <ul>
 *   <li>{@code registerwerk_event_publication_backlog} — count of Spring Modulith
 *       {@code event_publication} rows still awaiting processing. Spring Modulith's own
 *       {@code republish-outstanding-events-on-restart: true} (application.yml) silently retries
 *       these on every restart, but a backlog that keeps growing between restarts — a listener
 *       stuck failing, or simply falling behind — had no operator-visible signal at all.</li>
 *   <li>{@code registerwerk_shedlock_oldest_held_lock_age_seconds} — age of the oldest currently
 *       HELD (not-yet-expired) {@code shedlock} row. A ShedLock row is normally held for seconds
 *       to low minutes (a job's actual run time); a lock held for hours strongly suggests either
 *       a job hung mid-run without crashing (so nothing ever called
 *       {@code SchedulerLockConfig}'s release path) or a stalled instance holding a lock past its
 *       {@code lockAtMostFor} due to clock skew — either way, worth alerting on.</li>
 * </ul>
 *
 * <p>Both queries are cheap, whole-table facts rather than per-instance state (contrast
 * {@code RpcNodeHealthService.checkAll}, deliberately unlocked because it refreshes per-instance
 * in-memory routing) — locked here purely to avoid redundant identical queries from every
 * replica each tick, not because unlocked execution would be incorrect.
 */
@Component
class OperationalMetricsJob {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicLong eventPublicationBacklog = new AtomicLong();
    private final AtomicLong oldestHeldShedlockAgeSeconds = new AtomicLong();

    OperationalMetricsJob(JdbcTemplate jdbc, MeterRegistry meterRegistry, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("registerwerk.event_publication.backlog", eventPublicationBacklog, AtomicLong::get)
                .description("Spring Modulith event_publication rows not yet completed")
                .register(meterRegistry);
        Gauge.builder("registerwerk.shedlock.oldest_held_lock_age_seconds", oldestHeldShedlockAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest currently-held (unexpired) shedlock row; "
                        + "0 when no lock is currently held")
                .register(meterRegistry);
    }

    @SchedulerLock(name = "operationalMetricsRefresh", lockAtMostFor = "PT2M")
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void refresh() {
        Long backlog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Long.class);
        eventPublicationBacklog.set(backlog != null ? backlog : 0L);

        Timestamp oldestLockedAt = jdbc.query(
                "SELECT locked_at FROM shedlock WHERE lock_until > now() ORDER BY locked_at ASC LIMIT 1",
                rs -> rs.next() ? rs.getTimestamp(1) : null);
        long ageSeconds = oldestLockedAt == null
                ? 0L
                : Math.max(0L, Duration.between(oldestLockedAt.toInstant(), Instant.now(clock)).getSeconds());
        oldestHeldShedlockAgeSeconds.set(ageSeconds);
    }
}
