package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs each periodic screening in its own service transaction. */
@Component
public class ScreeningRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(ScreeningRefreshJob.class);

    private final ScreeningRunRepository runRepository;
    private final ScreeningService screeningService;
    private final AtomicInteger lastFailures = new AtomicInteger();

    public ScreeningRefreshJob(ScreeningRunRepository runRepository,
                               ScreeningService screeningService,
                               MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.screeningService = screeningService;
        Gauge.builder("registerwerk_screening_periodic_refresh_last_failures", lastFailures,
                        AtomicInteger::get)
                .description("Number of entities that failed re-screening in the most recent daily periodic refresh")
                .register(meterRegistry);
    }

    @SchedulerLock(name = "screeningPeriodicRefresh", lockAtMostFor = "PT2H")
    @Scheduled(cron = "0 0 1 * * *")
    public void periodicRefresh() {
        log.info("Starting periodic sanctions re-screening...");
        List<UUID> entityIds = runRepository.findDistinctActiveEntityIds();
        int succeeded = 0;
        int failed = 0;
        for (UUID entityId : entityIds) {
            try {
                screeningService.screenRegisteredEntity(entityId, ScreeningTrigger.PERIODIC_REFRESH);
                succeeded++;
            } catch (EntityNotFoundException deleted) {
                log.warn("Periodic screening: entity {} no longer exists, skipping.", entityId);
            } catch (Exception failure) {
                failed++;
                log.error("Periodic screening failed for entity={}", entityId, failure);
            }
        }
        lastFailures.set(failed);
        if (failed > 0) {
            log.warn("Periodic screening complete: {} succeeded, {} FAILED of {} attempted.",
                    succeeded, failed, entityIds.size());
        } else {
            log.info("Periodic screening complete: {} entities re-screened.", succeeded);
        }
    }
}
