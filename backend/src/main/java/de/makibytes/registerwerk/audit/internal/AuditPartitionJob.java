package de.makibytes.registerwerk.audit.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @PersistenceContext
    private EntityManager em;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        runEnsure("startup");
    }

    /** Monthly on the 1st at 02:00 UTC. */
    @Scheduled(cron = "0 0 2 1 * *")
    @Transactional
    public void ensurePartitions() {
        runEnsure("monthly schedule");
    }

    private void runEnsure(String trigger) {
        log.info("Ensuring audit_event partitions 6 months ahead (trigger: {})...", trigger);
        em.createNativeQuery("SELECT audit_event_ensure_partitions(6)").getSingleResult();
        log.info("audit_event partitions ensured.");
    }
}
