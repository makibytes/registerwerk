package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-runs subjects whose latest screening run failed (6-18), every 30 minutes, so a short provider
 * outage heals itself inside the gate's grace window. At most {@code retry-max-attempts} failed runs
 * per subject per 24 h; when that is reached an audited degraded alert is raised (no automatic
 * incident classification). Skipped in demo mode like the nightly refresh.
 */
@Component
public class ScreeningRetryJob {

    private static final Logger log = LoggerFactory.getLogger(ScreeningRetryJob.class);

    private final ScreeningRunRepository runRepository;
    private final ScreeningService screeningService;
    private final ScreeningPolicy policy;
    private final boolean demoSeeded;

    public ScreeningRetryJob(ScreeningRunRepository runRepository, ScreeningService screeningService,
                             ScreeningPolicy policy,
                             @Value("${registerwerk.seed-demo-data:false}") boolean demoSeeded) {
        this.runRepository = runRepository;
        this.screeningService = screeningService;
        this.policy = policy;
        this.demoSeeded = demoSeeded;
    }

    @SchedulerLock(name = "screeningRetry", lockAtMostFor = "PT25M")
    @Scheduled(fixedDelayString = "${registerwerk.screening.retry-delay:PT30M}",
            initialDelayString = "${registerwerk.screening.retry-delay:PT30M}")
    public void retryFailed() {
        if (demoSeeded) {
            return;
        }
        retryRound();
    }

    /** One retry pass; returns the number of subjects re-screened. Package-private for tests. */
    int retryRound() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        int retried = 0;
        int exhausted = 0;
        for (Object[] row : runRepository.findSubjectsWithLatestError()) {
            UUID entityId = (UUID) row[0];
            UUID personId = (UUID) row[1];
            long errors = entityId != null
                    ? runRepository.countByEntityIdAndStatusAndStartedAtAfter(entityId, ScreeningStatus.ERROR, since)
                    : runRepository.countByNaturalPersonIdAndStatusAndStartedAtAfter(personId, ScreeningStatus.ERROR, since);
            if (errors >= policy.retryMaxAttempts()) {
                if (errors == policy.retryMaxAttempts()) {
                    exhausted++;
                }
                continue;
            }
            try {
                ScreeningRun run = entityId != null
                        ? screeningService.screenRegisteredEntity(entityId, ScreeningTrigger.PERIODIC_REFRESH)
                        : screeningService.screenRegisteredNaturalPerson(personId, ScreeningTrigger.PERIODIC_REFRESH);
                retried++;
                if (run != null && run.getStatus() == ScreeningStatus.ERROR && errors + 1 >= policy.retryMaxAttempts()) {
                    exhausted++;
                }
            } catch (Exception e) {
                log.warn("Screening retry skipped for entity={} person={}: {}", entityId, personId, e.getMessage());
            }
        }
        if (exhausted > 0) {
            screeningService.reportDegraded("RETRIES_EXHAUSTED",
                    Map.of("subjects", exhausted, "maxAttempts", policy.retryMaxAttempts()));
        }
        return retried;
    }
}
