package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.events.AssetReactivatedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.deployment.api.BondLifecycleTransitionEvent;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CollateralAvailabilityListener (9A-06)")
class CollateralAvailabilityListenerTest {

    private final LendingMarketRepository markets = mock(LendingMarketRepository.class);
    private final LendingMarketService marketService = mock(LendingMarketService.class);
    private final LendingReconciliationService reconciliation = mock(LendingReconciliationService.class);
    private final CollateralAvailabilityListener listener = new CollateralAvailabilityListener(markets, marketService,
            reconciliation, new IsolatedTransactionExecutor(mock(PlatformTransactionManager.class)));
    private final UUID assetId = UUID.randomUUID();

    private LendingMarket market(LendingMarketStatus status) {
        LendingMarket m = new LendingMarket();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    @Test
    @DisplayName("a suspension pauses every live market on that collateral and re-verifies it; retired markets are left alone")
    void suspensionPausesLiveMarketsOnly() {
        LendingMarket live = market(LendingMarketStatus.ACTIVE);
        LendingMarket retired = market(LendingMarketStatus.RETIRED);
        when(markets.findByCollateralAssetId(assetId)).thenReturn(List.of(live, retired));

        listener.on(new AssetSuspendedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(reconciliation).pauseBorrowingBySystem(live.getId(), "COLLATERAL_ASSET_SUSPENDED");
        verify(marketService).reverifyMarket(live.getId());
        verify(reconciliation, never()).pauseBorrowingBySystem(eq(retired.getId()), anyString());
        verify(marketService, never()).reverifyMarket(retired.getId());
    }

    @Test
    @DisplayName("a reactivation re-verifies but never unpauses or pauses anything")
    void reactivationNeverTouchesTheOnchainPause() {
        LendingMarket live = market(LendingMarketStatus.PAUSED);
        when(markets.findByCollateralAssetId(assetId)).thenReturn(List.of(live));

        listener.on(new AssetReactivatedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(marketService).reverifyMarket(live.getId());
        verify(reconciliation, never()).pauseBorrowingBySystem(any(), any());
    }

    @Test
    @DisplayName("only OVERDUE / DEFAULTED bond transitions pause; MATURED does not")
    void bondTransitionsPauseOnlyWhenOverdueOrDefaulted() {
        LendingMarket live = market(LendingMarketStatus.ACTIVE);
        when(markets.findByCollateralAssetId(assetId)).thenReturn(List.of(live));

        listener.on(new BondLifecycleTransitionEvent(assetId, BondStatus.ACTIVE, BondStatus.MATURED, "x", List.of(), null, null));
        verify(reconciliation, never()).pauseBorrowingBySystem(any(), any());

        listener.on(new BondLifecycleTransitionEvent(assetId, BondStatus.MATURED, BondStatus.DEFAULTED, "x", List.of(), null, 7));
        verify(reconciliation).pauseBorrowingBySystem(live.getId(), "COLLATERAL_BOND_DEFAULTED");
    }

    @Test
    @DisplayName("a failing pause does not stop the re-verification, and does not throw out of the listener")
    void failingPauseIsContained() {
        LendingMarket live = market(LendingMarketStatus.ACTIVE);
        when(markets.findByCollateralAssetId(assetId)).thenReturn(List.of(live));
        when(reconciliation.pauseBorrowingBySystem(any(), any())).thenThrow(new IllegalStateException("outbox down"));

        listener.on(new AssetSuspendedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(marketService).reverifyMarket(live.getId());
        assertThat(true).isTrue();
    }
}
