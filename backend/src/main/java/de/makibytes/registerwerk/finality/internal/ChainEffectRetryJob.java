package de.makibytes.registerwerk.finality.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatically re-attempts {@code COMPENSATION_FAILED} rows below {@link #MAX_AUTO_RETRY_ATTEMPTS}
 * — the job {@link ChainEffectRecorder}'s and {@link FinalityJournalAdminService}'s javadoc both
 * already assumed exists. A row that has failed this many times stops being auto-retried (a
 * transient RPC blip resolves itself within a few passes; a row still failing after
 * {@link #MAX_AUTO_RETRY_ATTEMPTS} attempts needs a human, not another identical retry) but stays
 * {@code COMPENSATION_FAILED} — still visible in the unresolved-compensation queue, and still
 * retryable on demand via {@link FinalityJournalAdminService#retry}, which is not subject to this
 * cap since an operator retrying after fixing the underlying cause is a deliberate, informed act.
 */
@Component
class ChainEffectRetryJob {

    /** Past this many attempts, a row stops being auto-retried and waits for an operator — see
     *  class javadoc. */
    static final int MAX_AUTO_RETRY_ATTEMPTS = 10;

    private static final Logger log = LoggerFactory.getLogger(ChainEffectRetryJob.class);

    private final ChainEffectRepository repository;
    private final ChainEffectRetryExecutor retryExecutor;

    ChainEffectRetryJob(ChainEffectRepository repository, ChainEffectRetryExecutor retryExecutor) {
        this.repository = repository;
        this.retryExecutor = retryExecutor;
    }

    @SchedulerLock(name = "chainEffectRetryJob", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    void retryFailed() {
        // Deliberately no transaction around the batch. Every call below enters an independent
        // REQUIRES_NEW transaction, so a rollback-only failure cannot erase earlier successes.
        for (java.util.UUID chainEffectId : repository.findRetryableIds(
                ChainEffect.Status.COMPENSATION_FAILED, MAX_AUTO_RETRY_ATTEMPTS)) {
            try {
                retryExecutor.retry(chainEffectId);
            } catch (Exception e) {
                log.warn("ChainEffectRetryJob: retry of chain_effect={} threw: {}", chainEffectId, e.getMessage());
            }
        }
    }
}
