package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LendingReverifyJob (9A-06)")
class LendingReverifyJobTest {

    private final LendingMarketRepository markets = mock(LendingMarketRepository.class);
    private final LendingMarketService marketService = mock(LendingMarketService.class);
    private final LendingReconciliationService reconciliation = mock(LendingReconciliationService.class);
    private final LendingReleaseGate gate = mock(LendingReleaseGate.class);
    private final LendingReverifyJob job = new LendingReverifyJob(markets, marketService, reconciliation, gate,
            new IsolatedTransactionExecutor(mock(PlatformTransactionManager.class)));

    private LendingMarket market(LendingMarketStatus status) {
        LendingMarket m = new LendingMarket();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    @Test
    @DisplayName("reverifyScheduleIsDeclared: scheduled, locked across nodes, and holding no transaction of its own")
    void reverifyScheduleIsDeclared() throws Exception {
        var method = LendingReverifyJob.class.getMethod("reverifyAll");
        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString()).contains("reverify-interval-ms");
        assertThat(method.getAnnotation(SchedulerLock.class).name()).isEqualTo("lendingMarketReverify");
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    @DisplayName("every live market is re-verified; one whose collateral is unavailable is paused by the system; retired ones are skipped")
    void reverifiesLiveMarketsAndPausesUnavailableCollateral() {
        LendingMarket suspended = market(LendingMarketStatus.ACTIVE);
        LendingMarket healthy = market(LendingMarketStatus.ACTIVE);
        LendingMarket retired = market(LendingMarketStatus.RETIRED);
        when(gate.isReleased()).thenReturn(true);
        when(markets.findAll()).thenReturn(List.of(suspended, healthy, retired));
        when(markets.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(markets.findById(healthy.getId())).thenReturn(Optional.of(healthy));
        when(marketService.collateralUnavailableReason(suspended)).thenReturn(Optional.of("COLLATERAL_ASSET_SUSPENDED"));
        when(marketService.collateralUnavailableReason(healthy)).thenReturn(Optional.empty());

        job.reverifyAll();

        verify(marketService).reverifyMarket(suspended.getId());
        verify(marketService).reverifyMarket(healthy.getId());
        verify(marketService, never()).reverifyMarket(retired.getId());
        verify(reconciliation).pauseBorrowingBySystem(suspended.getId(), "COLLATERAL_ASSET_SUSPENDED");
        verify(reconciliation, never()).pauseBorrowingBySystem(org.mockito.ArgumentMatchers.eq(healthy.getId()), anyString());
    }

    @Test
    @DisplayName("nothing runs while the lending release gate is closed")
    void doesNothingWhileNotReleased() {
        when(gate.isReleased()).thenReturn(false);
        job.reverifyAll();
        verify(markets, never()).findAll();
        verify(marketService, never()).reverifyMarket(any());
    }
}
