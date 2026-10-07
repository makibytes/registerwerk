package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.events.AssetReactivatedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.deployment.api.BondLifecycleTransitionEvent;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 9A-06: borrowing happens as a direct wallet-to-contract call, so only the on-chain {@code borrowPaused} flag stops
 * it. When a collateral asset is suspended / put into redemption, or its bond goes OVERDUE / DEFAULTED, every live
 * market on that collateral is paused on-chain by the system ({@link LendingReconciliationService#pauseBorrowingBySystem})
 * and re-verified, instead of waiting for an operator to remember {@code POST /lending/markets/reverify}.
 *
 * <p>Asymmetric on purpose (SRE veto): a reactivation re-verifies the market (backend view) but NEVER unpauses on
 * chain; lifting a pause stays the 4-eyes {@code borrow-paused} action. No automatic pause of the token deployment
 * (it would also freeze repay / liquidate / lender withdrawals) - that is a legal-effect question (T9-02).
 *
 * <p>One {@code REQUIRES_NEW} transaction per market and step, so a failing RPC read or submit rolls back only
 * itself and never marks the listener's own transaction rollback-only.
 */
@Component
class CollateralAvailabilityListener {

    private static final Logger log = LoggerFactory.getLogger(CollateralAvailabilityListener.class);

    private final LendingMarketRepository markets;
    private final LendingMarketService marketService;
    private final LendingReconciliationService reconciliation;
    private final IsolatedTransactionExecutor isolated;

    CollateralAvailabilityListener(LendingMarketRepository markets, LendingMarketService marketService,
                                   LendingReconciliationService reconciliation, IsolatedTransactionExecutor isolated) {
        this.markets = markets;
        this.marketService = marketService;
        this.reconciliation = reconciliation;
        this.isolated = isolated;
    }

    @ApplicationModuleListener
    void on(AssetSuspendedEvent event) {
        handle(event.assetId(), "COLLATERAL_ASSET_SUSPENDED", true);
    }

    @ApplicationModuleListener
    void on(AssetRedeemedEvent event) {
        handle(event.assetId(), "COLLATERAL_ASSET_REDEMPTION_PENDING", true);
    }

    @ApplicationModuleListener
    void on(AssetReactivatedEvent event) {
        handle(event.assetId(), null, false);
    }

    @ApplicationModuleListener
    void on(BondLifecycleTransitionEvent event) {
        if (event.to() == BondStatus.OVERDUE || event.to() == BondStatus.DEFAULTED) {
            handle(event.assetId(), "COLLATERAL_BOND_" + event.to(), true);
        }
    }

    private void handle(UUID assetId, String pauseCause, boolean pause) {
        for (LendingMarket market : markets.findByCollateralAssetId(assetId)) {
            if (market.getStatus() == LendingMarketStatus.RETIRED) continue;
            UUID marketId = market.getId();
            if (pause) {
                step("auto-pause", marketId, () -> reconciliation.pauseBorrowingBySystem(marketId, pauseCause));
            }
            step("re-verify", marketId, () -> marketService.reverifyMarket(marketId));
        }
    }

    private void step(String what, UUID marketId, IsolatedTransactionExecutor.Work work) {
        try {
            isolated.run(work);
        } catch (RuntimeException e) {
            log.error("Lending market {}: {} after a collateral availability change failed (rolled back on its own; "
                    + "the hourly re-verification job retries): {}", marketId, what, e.getMessage(), e);
        }
    }
}
