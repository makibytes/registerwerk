package de.makibytes.registerwerk.asset.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T3-08: lapses allocations that were not paid within their window, so an unpaid allocation cannot
 * hold issue-size or holding-cap capacity forever.
 */
@Component
class SubscriptionOrderLapseJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionOrderLapseJob.class);
    private static final int BATCH = 200;

    private final SubscriptionOrderService service;

    SubscriptionOrderLapseJob(SubscriptionOrderService service) {
        this.service = service;
    }

    @SchedulerLock(name = "subscriptionOrderLapseJob", lockAtMostFor = "PT14M")
    @Scheduled(cron = "0 15 * * * *", zone = "${registerwerk.register.time-zone:Europe/Berlin}")
    void run() {
        int total = 0;
        int n;
        do {
            n = service.lapseExpiredAllocations(Instant.now(), BATCH);
            total += n;
        } while (n == BATCH);
        if (total > 0) {
            log.info("Lapsed {} unpaid subscription allocation(s)", total);
        }
    }
}
