package de.makibytes.registerwerk.auth.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps {@code login_attempt} bounded: unknown e-mails create rows, so expired ones are removed often. */
@Component
class LoginAttemptPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptPurgeJob.class);

    private final LoginAttemptLimiter limiter;

    LoginAttemptPurgeJob(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    @SchedulerLock(name = "loginAttemptPurge", lockAtMostFor = "PT9M")
    void purge() {
        int removed = limiter.purgeStale();
        if (removed > 0) {
            log.info("login_attempt purge removed {} expired row(s)", removed);
        }
    }
}
