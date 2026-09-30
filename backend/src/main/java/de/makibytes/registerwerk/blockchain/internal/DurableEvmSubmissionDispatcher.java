package de.makibytes.registerwerk.blockchain.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fleet-single retry loop for signed transactions prepared but not durably marked broadcast.
 * Candidates come pre-ordered and capped per signer (see {@link DurableEvmSubmissionService#dispatchCandidates});
 * a failed attempt is escalated to {@link OutboxRecoveryService} (automatic re-price of allow-listed,
 * non-regulatory calls; everything else waits for the operator).
 */
@Component
class DurableEvmSubmissionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DurableEvmSubmissionDispatcher.class);
    private final DurableEvmSubmissionService submissions;
    private final OutboxRecoveryService recovery;

    DurableEvmSubmissionDispatcher(DurableEvmSubmissionService submissions, OutboxRecoveryService recovery) {
        this.submissions = submissions;
        this.recovery = recovery;
    }

    @SchedulerLock(name = "durableEvmSubmissionDispatcher", lockAtMostFor = "PT1M")
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    void dispatchPending() {
        for (var candidate : submissions.dispatchCandidates()) {
            try {
                var outcome = submissions.dispatchWithOutcome(candidate.id());
                if (outcome == DurableEvmSubmissionService.DispatchOutcome.FAILED) {
                    recovery.escalate(candidate.id());
                }
            } catch (Exception e) {
                log.warn("Durable EVM submission {} remains pending: {}", candidate.id(), e.getMessage());
            }
        }
    }
}
