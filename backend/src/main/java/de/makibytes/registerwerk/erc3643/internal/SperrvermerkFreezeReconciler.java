package de.makibytes.registerwerk.erc3643.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry points of the Sperrvermerk on-chain sync (H5). Both are ShedLock-guarded, so exactly one instance
 * runs each pass.
 * <ul>
 *   <li>{@link #sweep()} - every few minutes: reads the outcome of submitted freezes/unfreezes the status event
 *       missed and retries failed ones with a backoff;</li>
 *   <li>{@link #reconcileNightly()} - nightly: compares every still-blocking block (ACTIVE or EXPIRY_REVIEW) with the
 *       tokens' {@code isFrozen} flags, re-sends missing/failed freezes and reports drift.</li>
 * </ul>
 */
@Component
class SperrvermerkFreezeReconciler {

    private final SperrvermerkFreezeService service;

    SperrvermerkFreezeReconciler(SperrvermerkFreezeService service) {
        this.service = service;
    }

    @SchedulerLock(name = "sperrvermerkFreezeSweep", lockAtMostFor = "PT10M")
    @Scheduled(fixedDelayString = "${registerwerk.sperrvermerk.freeze-sweep-ms:300000}",
            initialDelayString = "${registerwerk.sperrvermerk.freeze-sweep-initial-ms:120000}")
    public void sweep() {
        service.sweep();
    }

    @SchedulerLock(name = "sperrvermerkFreezeReconcile", lockAtMostFor = "PT1H")
    @Scheduled(cron = "${registerwerk.sperrvermerk.freeze-reconcile-cron:0 30 2 * * *}")
    public void reconcileNightly() {
        service.reconcile();
    }
}
