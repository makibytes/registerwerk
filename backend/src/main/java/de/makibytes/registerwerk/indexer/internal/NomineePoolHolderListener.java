package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.lending.events.LendingMarketRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Enters a newly registered lending market's contract as a nominee-pool holder of its collateral
 * asset (T2-18), so pledging collateral into it does not block the asset's holder sync. Lives in
 * {@code indexer} rather than in {@code LendingMarketService}: the register row is the indexer's
 * reconciliation concern, and {@code lending} must not write another module's holder table.
 * Runs after the registering transaction commits (Modulith event publication registry).
 */
@Component
class NomineePoolHolderListener {

    private static final Logger log = LoggerFactory.getLogger(NomineePoolHolderListener.class);

    private final NomineePoolHolderService nomineePoolHolderService;

    NomineePoolHolderListener(NomineePoolHolderService nomineePoolHolderService) {
        this.nomineePoolHolderService = nomineePoolHolderService;
    }

    @ApplicationModuleListener
    void onLendingMarketRegistered(LendingMarketRegisteredEvent event) {
        boolean registered = nomineePoolHolderService.registerLendingMarket(event.marketId(), null, "SYSTEM");
        log.info("Lending market {} nominee-pool registration: {}", event.marketId(), registered ? "done" : "skipped");
    }
}
