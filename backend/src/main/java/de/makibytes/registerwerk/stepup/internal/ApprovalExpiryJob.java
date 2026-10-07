package de.makibytes.registerwerk.stepup.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks approval requests nobody decided (or the requester never claimed) in time EXPIRED. */
@Component
class ApprovalExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryJob.class);

    private final ApprovalRequestService service;

    ApprovalExpiryJob(ApprovalRequestService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${registerwerk.auth.step-up.approval-queue.sweep-interval:PT1M}")
    @SchedulerLock(name = "approvalRequestExpiry", lockAtMostFor = "PT4M")
    void sweep() {
        int n = service.expireStale();
        if (n > 0) {
            log.info("Expired {} approval request(s)", n);
        }
    }
}
