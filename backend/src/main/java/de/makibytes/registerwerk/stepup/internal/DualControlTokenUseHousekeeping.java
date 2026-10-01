package de.makibytes.registerwerk.stepup.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Prunes consumed approver tokens once they could no longer be presented anyway. */
@Component
class DualControlTokenUseHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(DualControlTokenUseHousekeeping.class);

    private final DualControlTokenUseRepository repository;

    DualControlTokenUseHousekeeping(DualControlTokenUseRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 20 * * * *")
    @SchedulerLock(name = "dualControlTokenUsePrune", lockAtMostFor = "PT10M")
    void prune() {
        int n = repository.pruneExpired();
        if (n > 0) {
            log.debug("Pruned {} expired dual-control token use row(s)", n);
        }
    }
}
