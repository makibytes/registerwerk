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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Arbitrary constants: one advisory-lock key per job, so replicas starting together run it once. */
    static final long LOCK_KEY = 0x5061727431L;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Map<String, Double> defaultRows = new ConcurrentHashMap<>();
    private volatile long defaultRowsAt;

    PartitionMaintenanceJob(PlatformTransactionManager txManager, MeterRegistry registry, JdbcTemplate jdbc) {
        this.tx = new TransactionTemplate(txManager);
        this.registry = registry;
        this.jdbc = jdbc;
        for (String table : new String[]{"token_transfer", "blockchain_transaction", "audit_event"}) {
            Gauge.builder("registerwerk_partition_default_rows", this, j -> j.defaultRows(table))
                    .tag("table", table)
                    .description("Rows in the DEFAULT partition (a row there blocks creating its month); -1 on read failure")
                    .register(registry);
        }
    }

    /** Count of rows in {@code <table>_default}, cached 5 minutes. */
    private double defaultRows(String table) {
        long now = System.currentTimeMillis();
        if (now - defaultRowsAt > 300_000) {
            defaultRowsAt = now;
            for (String t : new String[]{"token_transfer", "blockchain_transaction", "audit_event"}) {
                try {
                    Long n = jdbc.queryForObject("SELECT count(*) FROM " + t + "_default", Long.class);
                    defaultRows.put(t, n == null ? 0.0 : n.doubleValue());
                } catch (RuntimeException e) {
                    defaultRows.put(t, -1.0);
                }
            }
        }
        return defaultRows.getOrDefault(table, -1.0);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        guardedEnsure("startup");
    }

    /** Monthly on the 1st at 02:15 UTC — 15 minutes after AuditPartitionJob's own monthly run,
     *  so the two never contend over the same window even though they lock independently. */
    @SchedulerLock(name = "partitionMaintenanceEnsure", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 15 2 1 * *")
    public void ensurePartitions() {
        guardedEnsure("monthly schedule");
    }

    /**
     * 7A-11: under a transaction-scoped advisory lock (replicas starting together run it once) and never
     * aborting startup: a failure (e.g. a stray row in the DEFAULT partition blocking a month) is logged,
     * counted and left to the scheduled retry; the application stays ready.
     */
    void guardedEnsure(String trigger) {
        try {
            tx.executeWithoutResult(status -> {
                Object got = em.createNativeQuery("SELECT pg_try_advisory_xact_lock(" + LOCK_KEY + ")").getSingleResult();
                if (!Boolean.TRUE.equals(got)) {
                    log.info("Partition maintenance ({}) skipped: another instance holds the lock", trigger);
                    return;
                }
                runEnsure(trigger);
            });
        } catch (RuntimeException e) {
            registry.counter("registerwerk_partition_ensure_failures_total", "job", "partition_maintenance").increment();
            log.error("Partition maintenance ({}) failed - startup continues, the monthly run retries. Check the "
                    + "*_default partitions for rows that block a month: {}", trigger, e.toString());
        }
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
