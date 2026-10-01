package de.makibytes.registerwerk.audit.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily prune of the audit dedup ids (7A-05). 30 days is far beyond the resubmission horizon: audit
 * publications older than that are dead-lettered by {@link AuditResubmissionJob} long before.
 */
@Component
class AuditRecordIdPruneJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRecordIdPruneJob.class);
    private final JdbcTemplate jdbc;

    AuditRecordIdPruneJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @SchedulerLock(name = "auditRecordIdPrune", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 15 4 * * *")
    void prune() {
        int n = jdbc.update("DELETE FROM audit_event_record_id WHERE recorded_at < now() - interval '30 days'");
        if (n > 0) {
            log.info("Pruned {} audit dedup ids older than 30 days", n);
        }
    }
}
