package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean demoSeeded;

    private final de.makibytes.registerwerk.shared.ProductionMode productionMode;
    private final MeterRegistry meterRegistry;

    public ScreeningRefreshJob(ScreeningRunRepository runRepository, ScreeningService screeningService,
                               MeterRegistry meterRegistry, boolean demoSeeded) {
        this(runRepository, screeningService, meterRegistry, demoSeeded,
                de.makibytes.registerwerk.shared.ProductionMode.of(false));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScreeningRefreshJob(ScreeningRunRepository runRepository, ScreeningService screeningService,
                               MeterRegistry meterRegistry,
                               @Value("${registerwerk.seed-demo-data:false}") boolean demoSeeded,
                               org.springframework.core.env.Environment environment) {
        this(runRepository, screeningService, meterRegistry, demoSeeded, de.makibytes.registerwerk.shared.ProductionMode.of(environment));
    }

    public ScreeningRefreshJob(ScreeningRunRepository runRepository,
                               ScreeningService screeningService,
                               MeterRegistry meterRegistry,
                               boolean demoSeeded,
                               de.makibytes.registerwerk.shared.ProductionMode productionMode) {
        this.productionMode = productionMode;
        this.meterRegistry = meterRegistry;
        this.demoSeeded = demoSeeded;
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
        if (demoSeeded && productionMode.enabled()) {
            // 7A-01 belt and braces: unreachable (the readiness check refuses it), but never skip silently.
            log.error("registerwerk.seed-demo-data=true in production mode: running the sanctions re-screening anyway");
            meterRegistry.counter("registerwerk_screening_job_skipped_total", "reason", "demo_seeded_in_production").increment();
        } else if (demoSeeded) {
            // The demo stack has no reachable screening provider: a re-screen would append an ERROR run per
            // demo party, which becomes the "latest" run and makes the fail-closed gates refuse them again.
            log.info("Periodic sanctions re-screening skipped: demo data is seeded (registerwerk.seed-demo-data=true).");
            meterRegistry.counter("registerwerk_screening_job_skipped_total", "reason", "demo_seeded").increment();
            return;
        }
        log.info("Starting periodic sanctions re-screening...");
        List<UUID> entityIds = runRepository.findDistinctActiveEntityIds();
        int succeeded = 0;
        int failed = 0;
        for (UUID entityId : entityIds) {
            try {
                ScreeningRun run = screeningService.screenRegisteredEntity(entityId, ScreeningTrigger.PERIODIC_REFRESH);
                if (run != null && run.getStatus() == ScreeningStatus.ERROR) {
                    failed++; // provider errors are recorded as ERROR runs, not thrown
                } else {
                    succeeded++;
                }
            } catch (EntityNotFoundException deleted) {
                log.warn("Periodic screening: entity {} no longer exists, skipping.", entityId);
            } catch (Exception failure) {
                failed++;
                log.error("Periodic screening failed for entity={}", entityId, failure);
            }
        }
        lastFailures.set(failed);
        if (failed > 0) {
            screeningService.reportDegraded("PERIODIC_REFRESH_FAILURES",
                    java.util.Map.of("failed", failed, "attempted", entityIds.size()));
            log.warn("Periodic screening complete: {} succeeded, {} FAILED of {} attempted.",
                    succeeded, failed, entityIds.size());
        } else {
            log.info("Periodic screening complete: {} entities re-screened.", succeeded);
        }
    }
}
