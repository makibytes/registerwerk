package de.makibytes.registerwerk.wallet.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * P4C-5: destroys key material of soft-deleted operator wallets once
 * {@code registerwerk.wallet.retention-days} (default 90) have passed.
 */
@Component
class WalletPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(WalletPurgeJob.class);

    private final WalletService walletService;
    private final long retentionDays;

    WalletPurgeJob(WalletService walletService,
                   @Value("${registerwerk.wallet.retention-days:90}") long retentionDays) {
        this.walletService = walletService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${registerwerk.wallet.purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "walletPurge", lockAtMostFor = "PT30M")
    void purge() {
        int purged = walletService.purgeExpired(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (purged > 0) {
            log.info("Wallet purge: {} tombstoned wallet(s) past retention removed", purged);
        }
    }
}
