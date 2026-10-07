package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 9A-06: the market re-verification ({@link LendingMarketService#reverifyMarket}) used to run only from
 * {@code POST /lending/markets/reverify}. Now it runs hourly, and a market whose collateral can no longer back new
 * borrowing (live asset / bond state, which also catches TRANSFER_PENDING / TRANSFERRED_OUT without any event) is
 * paused on-chain by the system. The job holds no transaction of its own; every market is handled in its own
 * {@code REQUIRES_NEW} transaction (the service is class-level transactional and would otherwise keep one DB
 * transaction open across N RPC calls).
 */
@Component
class LendingReverifyJob {

    private static final Logger log = LoggerFactory.getLogger(LendingReverifyJob.class);

    private final LendingMarketRepository markets;
    private final LendingMarketService marketService;
    private final LendingReconciliationService reconciliation;
    private final LendingReleaseGate releaseGate;
    private final IsolatedTransactionExecutor isolated;

    LendingReverifyJob(LendingMarketRepository markets, LendingMarketService marketService,
                       LendingReconciliationService reconciliation, LendingReleaseGate releaseGate,
                       IsolatedTransactionExecutor isolated) {
        this.markets = markets;
        this.marketService = marketService;
        this.reconciliation = reconciliation;
        this.releaseGate = releaseGate;
        this.isolated = isolated;
    }

    @SchedulerLock(name = "lendingMarketReverify", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Scheduled(fixedDelayString = "${registerwerk.lending.reverify-interval-ms:3600000}", initialDelay = 120_000)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reverifyAll() {
        if (!releaseGate.isReleased()) return;
        List<LendingMarket> live = markets.findAll().stream()
                .filter(m -> m.getStatus() != LendingMarketStatus.RETIRED).toList();
        for (LendingMarket scanned : live) {
            UUID marketId = scanned.getId();
            try {
                isolated.run(() -> marketService.reverifyMarket(marketId));
            } catch (RuntimeException e) {
                log.error("Hourly re-verification of lending market {} failed (rolled back on its own): {}",
                        marketId, e.getMessage(), e);
            }
            try {
                isolated.run(() -> markets.findById(marketId)
                        .flatMap(marketService::collateralUnavailableReason)
                        .ifPresent(cause -> reconciliation.pauseBorrowingBySystem(marketId, cause)));
            } catch (RuntimeException e) {
                log.error("Automatic borrow pause of lending market {} failed (rolled back on its own; the next run "
                        + "retries): {}", marketId, e.getMessage(), e);
            }
        }
    }
}
