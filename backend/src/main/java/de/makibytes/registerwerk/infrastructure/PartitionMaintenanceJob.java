package de.makibytes.registerwerk.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the monthly RANGE-partitioned {@code token_transfer} and
 * {@code blockchain_transaction} tables bootstrapped with
 * partitions for months ahead, using the generic {@code rw_ensure_monthly_partitions(regclass,
 * text, int)} database function.
 *
 * <p>Mirrors {@code audit.internal.AuditPartitionJob} exactly (startup run + monthly cron; the
 * startup run matters for the same reason documented there: a cron-only schedule silently skips
 * an instance that is down at the trigger time, and once rows fall into the DEFAULT partition
 * for a range, Postgres refuses to carve out the proper monthly partition for it afterwards).
 * Deliberately does NOT touch {@code audit_event} — that stays exclusively
 * {@code AuditPartitionJob}'s responsibility, calling the original, unmodified
 * {@code audit_event_ensure_partitions()} function; this job only exists because token_transfer
 * and blockchain_transaction needed the same "keep creating future months" behavior once they
 * became partitioned tables in .
 *
 * <p>Only ever calls the "ensure" (create) side of the generic partition mechanism, never
 * {@code rw_retire_partitions} — retiring/detaching old partitions of these two tables is a
 * separate, not-yet-made policy decision (see the V3 migration header and
 * {@code RetentionSweepJob}'s class Javadoc for what deliberately leaves unautomated).
 */
@Component
class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final int MONTHS_AHEAD = 6;

    @PersistenceContext
    private EntityManager em;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        runEnsure("startup");
    }

    /** Monthly on the 1st at 02:15 UTC — 15 minutes after AuditPartitionJob's own monthly run,
     *  so the two never contend over the same window even though they lock independently. */
    @SchedulerLock(name = "partitionMaintenanceEnsure", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 15 2 1 * *")
    @Transactional
    public void ensurePartitions() {
        runEnsure("monthly schedule");
    }

    private void runEnsure(String trigger) {
        log.info("Ensuring token_transfer/blockchain_transaction partitions {} months ahead (trigger: {})...",
                MONTHS_AHEAD, trigger);
        em.createNativeQuery("SELECT rw_ensure_monthly_partitions('token_transfer', 'occurred_at', "
                + MONTHS_AHEAD + ")").getSingleResult();
        em.createNativeQuery("SELECT rw_ensure_monthly_partitions('blockchain_transaction', 'created_at', "
                + MONTHS_AHEAD + ")").getSingleResult();
        log.info("token_transfer/blockchain_transaction partitions ensured.");
    }
}
