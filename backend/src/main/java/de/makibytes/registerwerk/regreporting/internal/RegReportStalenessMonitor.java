package de.makibytes.registerwerk.regreporting.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Daily check for transported drafts that still lack authenticated authority evidence.
 *
 * <p>SFTP can prove only a byte write. This environment has no authenticated authority receipt
 * or correction reconciliation, so {@code TRANSPORTED_UNVERIFIED} must never be promoted to
 * acceptance. The monitor surfaces old transport evidence, the same way
 * {@code DoraService.checkDeadlines()} and
 * {@code AuditChainVerificationService.verify()} both already surface their own deadline/
 * integrity breaches in this codebase — loud logging today, wireable to a real alert channel
 * later without changing this job's shape.
 */
@Component
class RegReportStalenessMonitor {

    private static final Logger log = LoggerFactory.getLogger(RegReportStalenessMonitor.class);

    private final JdbcTemplate jdbc;
    private final ReportingProperties props;

    RegReportStalenessMonitor(JdbcTemplate jdbc, ReportingProperties props, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.props = props;

        // Live-queried at scrape time, reusing the exact query checkStaleSubmissions() already
        // runs daily — this class's own Javadoc anticipated this follow-up ("loud logging today,
        // wireable to a real alert channel later without changing this job's shape").
        Gauge.builder("registerwerk_regreport_stale_submissions_total", this, RegReportStalenessMonitor::countStaleSubmissions)
                .description("Count of transported draft reports still lacking verified authority evidence beyond the threshold")
                .register(meterRegistry);
    }

    private double countStaleSubmissions() {
        Instant cutoff = Instant.now().minus(props.getStaleSubmissionThresholdDays(), ChronoUnit.DAYS);
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM regreport_submission
            WHERE status = 'TRANSPORTED_UNVERIFIED' AND transported_at < ?
            """, Long.class, cutoff);
        return count == null ? 0.0 : count;
    }

    @SchedulerLock(name = "regReportStalenessCheck", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 15 7 * * *")
    @Transactional(readOnly = true)
    public void checkStaleSubmissions() {
        Instant cutoff = Instant.now().minus(props.getStaleSubmissionThresholdDays(), ChronoUnit.DAYS);
        List<UUID> stale = jdbc.queryForList("""
            SELECT id FROM regreport_submission
            WHERE status = 'TRANSPORTED_UNVERIFIED' AND transported_at < ?
            """, UUID.class, cutoff);
        if (!stale.isEmpty()) {
            log.error("REGULATORY REPORTING PROTOTYPE: {} transported draft(s) lack verified authority evidence for more than {} day(s): {}",
                    stale.size(), props.getStaleSubmissionThresholdDays(), stale);
        }
    }
}
