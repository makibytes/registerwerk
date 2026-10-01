package de.makibytes.registerwerk.audit.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ensures monthly audit_event partitions exist up to 6 months ahead.
 * Calls the audit_event_ensure_partitions() DB function (V1__initial_schema.sql).
 *
 * <p>Runs on application startup <em>and</em> monthly on the 1st at 02:00 UTC.
 * The startup run is essential: a cron-only schedule silently skips when the
 * instance is down at the trigger time, and once audit rows fall into the
 * DEFAULT partition, Postgres refuses to create the proper monthly partition
 * for that range ("updated partition constraint for default partition would
 * be violated") — a permanent breakage requiring manual data movement. Any
 * restart heals missed runs.
 */
@Component
class AuditPartitionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionJob.class);

    static final long LOCK_KEY = 0x4175646974L;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;
    private final MeterRegistry registry;

    AuditPartitionJob(PlatformTransactionManager txManager, MeterRegistry registry) {
        this.tx = new TransactionTemplate(txManager);
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        guardedEnsure("startup");
    }

    /** Monthly on the 1st at 02:00 UTC. */
    @SchedulerLock(name = "auditPartitionEnsure", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 0 2 1 * *")
    public void ensurePartitions() {
        guardedEnsure("monthly schedule");
    }

    /** 7A-11: advisory-locked, and a failure is logged and counted instead of aborting startup. */
    void guardedEnsure(String trigger) {
        try {
            tx.executeWithoutResult(status -> {
                Object got = em.createNativeQuery("SELECT pg_try_advisory_xact_lock(" + LOCK_KEY + ")").getSingleResult();
                if (!Boolean.TRUE.equals(got)) {
                    log.info("Audit partition maintenance ({}) skipped: another instance holds the lock", trigger);
                    return;
                }
                runEnsure(trigger);
            });
        } catch (RuntimeException e) {
            registry.counter("registerwerk_partition_ensure_failures_total", "job", "audit_partition").increment();
            log.error("Audit partition maintenance ({}) failed - startup continues, the monthly run retries: {}",
                    trigger, e.toString());
        }
    }

    private void runEnsure(String trigger) {
        log.info("Ensuring audit_event partitions 6 months ahead (trigger: {})...", trigger);
        em.createNativeQuery("SELECT audit_event_ensure_partitions(6)").getSingleResult();
        log.info("audit_event partitions ensured.");
    }
}
