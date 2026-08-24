package de.makibytes.registerwerk.blockchain.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fleet-single retry loop for signed transactions prepared but not durably marked broadcast. */
@Component
class DurableEvmSubmissionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DurableEvmSubmissionDispatcher.class);
    private final DurableEvmSubmissionService submissions;

    DurableEvmSubmissionDispatcher(DurableEvmSubmissionService submissions) {
        this.submissions = submissions;
    }

    @SchedulerLock(name = "durableEvmSubmissionDispatcher", lockAtMostFor = "PT1M")
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    void dispatchPending() {
        for (var id : submissions.pendingIds()) {
            try {
                submissions.dispatch(id);
            } catch (Exception e) {
                log.warn("Durable EVM submission {} remains pending: {}", id, e.getMessage());
            }
        }
    }
}
