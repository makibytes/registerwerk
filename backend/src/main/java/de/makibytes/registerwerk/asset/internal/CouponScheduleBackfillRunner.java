package de.makibytes.registerwerk.asset.internal;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One-off, idempotent startup backfill for T3-04: every bond with a positive coupon rate that has
 * no coupon-schedule rows at all gets its schedule generated — future periods only, so no
 * retroactive MISSED coupons appear. Bonds that already have any row are left alone, which makes
 * repeated runs (restarts, several instances) harmless; ShedLock keeps concurrent instances from
 * racing on the same bond. Runs after the demo seeders ({@link #getOrder()}), so seeded bonds are
 * covered by the same path.
 */
@Component
class CouponScheduleBackfillRunner implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CouponScheduleBackfillRunner.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final CouponScheduleService scheduleService;
    private final LockProvider lockProvider;

    CouponScheduleBackfillRunner(CouponScheduleService scheduleService, LockProvider lockProvider) {
        this.scheduleService = scheduleService;
        this.lockProvider = lockProvider;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void run(ApplicationArguments args) {
        new DefaultLockingTaskExecutor(lockProvider).executeWithLock((Runnable) this::backfill,
                new LockConfiguration(Instant.now(), "couponScheduleBackfill", Duration.ofMinutes(10), Duration.ZERO));
    }

    void backfill() {
        List<UUID> missing = scheduleService.bondsMissingSchedule();
        int bonds = 0;
        int rows = 0;
        for (UUID assetId : missing) {
            try {
                int written = scheduleService.regenerate(assetId, SYSTEM_ACTOR, "SYSTEM", CouponScheduleService.TRIGGER_BACKFILL);
                if (written > 0) {
                    bonds++;
                    rows += written;
                }
            } catch (RuntimeException e) {
                log.error("Coupon schedule backfill failed for asset={}: {}", assetId, e.getMessage());
            }
        }
        log.info("Coupon schedule backfill: {} bond(s) without a schedule, {} backfilled with {} row(s).",
                missing.size(), bonds, rows);
    }
}
