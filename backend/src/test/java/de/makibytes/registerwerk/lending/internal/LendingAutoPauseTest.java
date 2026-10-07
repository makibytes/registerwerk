package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTaskRepository;
import de.makibytes.registerwerk.lending.events.LendingOperatorActionEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LendingReconciliationService.pauseBorrowingBySystem (9A-06)")
class LendingAutoPauseTest {

    private final LendingMarketRepository markets = mock(LendingMarketRepository.class);
    private final RepoMarketOnchainReader reader = mock(RepoMarketOnchainReader.class);
    private final LendingMarketService marketService = mock(LendingMarketService.class);
    private final DurableEvmTransactionGateway durable = mock(DurableEvmTransactionGateway.class);
    private final LendingReleaseGate gate = mock(LendingReleaseGate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private LendingReconciliationService service;
    private LendingMarket market;

    @BeforeEach
    void setUp() {
        service = new LendingReconciliationService(markets, mock(LendingReconciliationTaskRepository.class), reader,
                marketService, durable, gate, events, meters, mock(PlatformTransactionManager.class));
        market = new LendingMarket();
        ReflectionTestUtils.setField(market, "id", UUID.randomUUID());
        market.setChainConfigId(UUID.randomUUID());
        market.setMarketAddress("0x1111111111111111111111111111111111111a");
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(gate.isReleased()).thenReturn(true);
        when(marketService.requireMarket(market.getId())).thenReturn(market);
        when(marketService.resolveChainIdentifier(market.getChainConfigId())).thenReturn("ETHEREUM_SEPOLIA");
        when(durable.submit(any(), anyString(), any(), any())).thenReturn("0xtx");
    }

    @Test
    @DisplayName("an unpaused market gets exactly one setBorrowPaused(true) as SYSTEM, audited; a repeat is suppressed")
    void submitsOnceAndAuditsAsSystem() {
        when(reader.borrowPaused("ETHEREUM_SEPOLIA", market.getMarketAddress())).thenReturn(false);

        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).contains("0xtx");
        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).isEmpty();

        verify(durable, times(1)).submit(eq(market.getChainConfigId()), eq(market.getMarketAddress()), any(), any());
        ArgumentCaptor<LendingOperatorActionEvent> event = ArgumentCaptor.forClass(LendingOperatorActionEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("LENDING_BORROW_PAUSE_SUBMITTED");
        assertThat(event.getValue().actorId()).isNull();
        assertThat(event.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat((Map<String, Object>) event.getValue().payload()).containsEntry("automatic", true)
                .containsEntry("cause", "COLLATERAL_ASSET_SUSPENDED");
        assertThat(meters.counter("registerwerk_lending_market_auto_paused_total", "cause",
                "COLLATERAL_ASSET_SUSPENDED").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nothing is submitted when the chain already reads borrowPaused, when it cannot be read, or when not released")
    void submitsNothingWhenAlreadyPausedUnreadableOrUnreleased() {
        when(reader.borrowPaused("ETHEREUM_SEPOLIA", market.getMarketAddress())).thenReturn(true);
        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).isEmpty();

        when(reader.borrowPaused("ETHEREUM_SEPOLIA", market.getMarketAddress())).thenThrow(new IllegalStateException("rpc"));
        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).isEmpty();

        when(gate.isReleased()).thenReturn(false);
        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).isEmpty();

        verify(durable, never()).submit(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a RETIRED market is never paused")
    void retiredMarketIsSkipped() {
        market.setStatus(LendingMarketStatus.RETIRED);
        assertThat(service.pauseBorrowingBySystem(market.getId(), "COLLATERAL_ASSET_SUSPENDED")).isEmpty();
        verify(durable, never()).submit(any(), anyString(), any(), any());
    }
}
