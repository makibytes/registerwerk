package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ends expired impersonation sessions (emitting the audit event) and prunes spent revocations. */
@Component
class SessionHousekeepingJob {

    private final ImpersonationSessionService impersonations;
    private final SessionRevocationRepository revocations;

    SessionHousekeepingJob(ImpersonationSessionService impersonations, SessionRevocationRepository revocations) {
        this.impersonations = impersonations;
        this.revocations = revocations;
    }

    @Scheduled(fixedDelayString = "${registerwerk.auth.session-housekeeping-ms:60000}", initialDelay = 60000)
    @SchedulerLock(name = "sessionHousekeeping", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @org.springframework.transaction.annotation.Transactional
    void run() {
        impersonations.endExpired();
        revocations.deleteExpired(Instant.now().minus(1, ChronoUnit.DAYS));
    }
}
