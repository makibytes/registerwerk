package de.makibytes.registerwerk.audit.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures monthly audit_event partitions exist up to 6 months ahead.
 * Calls the audit_event_ensure_partitions() DB function from V10__audit_chain.sql.
 *
 * URGENT: without this job, writes after 2026-06-01 fall into the DEFAULT partition
 * which bypasses the monthly-partition retention and compaction strategy.
 */
@Component
class AuditPartitionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionJob.class);

    @PersistenceContext
    private EntityManager em;

    /** Run on startup and then monthly on the 1st at 02:00 UTC. */
    @Scheduled(cron = "0 0 2 1 * *")
    @Transactional
    public void ensurePartitions() {
        log.info("Ensuring audit_event partitions 6 months ahead...");
        em.createNativeQuery("SELECT audit_event_ensure_partitions(6)").getSingleResult();
        log.info("audit_event partitions ensured.");
    }
}
