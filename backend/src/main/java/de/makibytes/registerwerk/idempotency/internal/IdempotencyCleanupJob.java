package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.IdempotencyRecord;
import de.makibytes.registerwerk.idempotency.api.IdempotencyRecordRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Purges idempotency records older than 48h — the retention window a client's retry logic is
 *  realistically going to need, not a permanent log (that's what {@code audit_event} is for). */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final int RETENTION_HOURS = 48;

    private final IdempotencyRecordRepository repository;

    IdempotencyCleanupJob(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "idempotencyCleanup", lockAtMostFor = "PT10M")
    @Transactional
    public void purgeExpiredRecords() {
        Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        List<IdempotencyRecord> expired = repository.findByCreatedAtBefore(cutoff);
        if (!expired.isEmpty()) {
            repository.deleteAll(expired);
            log.info("Purged {} expired idempotency record(s).", expired.size());
        }
    }
}
