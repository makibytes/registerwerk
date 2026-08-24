package de.makibytes.registerwerk.infrastructure;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, against a real Postgres container, that {@link OperationalMetricsJob} computes the
 * event_publication backlog and the oldest-held-ShedLock-age gauges correctly.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("OperationalMetricsJob")
class OperationalMetricsJobIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private OperationalMetricsJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("counts incomplete event_publication rows and the oldest currently-held ShedLock's age")
    void refresh_computesBacklogAndOldestHeldLockAge() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM shedlock");

        // Two incomplete (still-pending) publications, one already completed — only the two
        // incomplete ones should count toward the backlog.
        insertEventPublication(true);
        insertEventPublication(true);
        insertEventPublication(false);

        // One lock held (unexpired) 5 minutes ago, one already expired — only the held one
        // should be picked up, and its age should be ~300s.
        Instant heldSince = Instant.now().minusSeconds(300);
        jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, ?)",
                "heldLock", java.sql.Timestamp.from(Instant.now().plusSeconds(600)),
                java.sql.Timestamp.from(heldSince), "test-instance");
        jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, ?, ?, ?)",
                "expiredLock", java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), "test-instance");

        ReflectionTestUtils.invokeMethod(job, "refresh");

        double backlog = meterRegistry.get("registerwerk.event_publication.backlog").gauge().value();
        assertThat(backlog).isEqualTo(2.0);

        double lockAge = meterRegistry.get("registerwerk.shedlock.oldest_held_lock_age_seconds").gauge().value();
        assertThat(lockAge).isBetween(295.0, 320.0);
    }

    private void insertEventPublication(boolean incomplete) {
        // completion_attempts=0 (not left NULL): Spring Modulith's own JPA event-publication
        // registry re-reads this table at context shutdown to look for outstanding publications
        // to republish, and maps completion_attempts to a primitive int — a NULL there throws
        // during that shutdown pass, which is noisy (though harmless to this test's own outcome).
        jdbc.update("""
                INSERT INTO event_publication
                    (id, listener_id, event_type, serialized_event, publication_date, completion_date,
                     completion_attempts)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), "test.listener", "java.lang.Object", "{}",
                java.sql.Timestamp.from(Instant.now()),
                incomplete ? null : java.sql.Timestamp.from(Instant.now()));
    }
}
