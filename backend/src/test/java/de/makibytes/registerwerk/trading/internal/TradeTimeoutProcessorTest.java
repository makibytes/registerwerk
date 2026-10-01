package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradePaymentUnresolvedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingTimedOutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TradeTimeoutProcessor (5A-03)")
class TradeTimeoutProcessorTest {

    private final UUID executionId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();
    private TradeExecutionRepository executions;
    private TradeListingRepository listings;
    private ApplicationEventPublisher events;
    private TradingProperties properties;
    private TradeTimeoutProcessor processor;
    private Instant cutoff;

    @BeforeEach
    void setUp() {
        executions = mock(TradeExecutionRepository.class);
        listings = mock(TradeListingRepository.class);
        events = mock(ApplicationEventPublisher.class);
        properties = new TradingProperties();
        properties.setPendingTimeoutHours(72);
        processor = new TradeTimeoutProcessor(executions, new TradeTransitions(executions, listings, events, properties), properties);
        cutoff = Instant.now().minus(72, ChronoUnit.HOURS);
    }

    private TradeExecution execution(SettlementStatus status, Instant createdAt, Instant declaredAt) {
        TradeExecution e = new TradeExecution();
        ReflectionTestUtils.setField(e, "id", executionId);
        ReflectionTestUtils.setField(e, "createdAt", createdAt);
        e.setListingId(listingId);
        e.setBuyerEntityId(UUID.randomUUID());
        e.setSellerEntityId(UUID.randomUUID());
        e.setExecutedQuantity(BigDecimal.valueOf(4));
        e.setSettlementStatus(status);
        e.setPaymentDeclaredAt(declaredAt);
        when(executions.findByIdForUpdate(executionId)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("PENDING without a declaration -> FAILED, quantity restored, buyer cool-down set, timed-out event")
    void pendingTimesOutToFailedAndRestoresQuantity() {
        TradeExecution e = execution(SettlementStatus.PENDING, Instant.now().minus(80, ChronoUnit.HOURS), null);
        TradeListing listing = new TradeListing();
        listing.setStatus(ListingStatus.PARTIALLY_FILLED);
        listing.setQuantityAvailable(BigDecimal.ONE);
        when(listings.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));

        assertThat(processor.timeoutPending(executionId, cutoff)).isTrue();

        assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(e.getBuyerCooldownUntil()).isNotNull();
        assertThat(listing.getQuantityAvailable()).isEqualByComparingTo("5");
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
        verify(events).publishEvent(any(TradePendingTimedOutEvent.class));
    }

    @Test
    @DisplayName("declared payment never confirmed -> PAYMENT_UNRESOLVED, reservation kept, listing untouched")
    void awaitingTimesOutToUnresolvedNotFailed() {
        TradeExecution e = execution(SettlementStatus.AWAITING_SELLER_CONFIRMATION, Instant.now().minus(90, ChronoUnit.HOURS),
                Instant.now().minus(80, ChronoUnit.HOURS));

        assertThat(processor.timeoutAwaiting(executionId, cutoff)).isTrue();

        assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(e.getUnresolvedAt()).isNotNull();
        assertThat(e.getUnresolvedReason()).contains("TIMEOUT");
        verify(listings, never()).findByIdForUpdate(any());
        verify(events).publishEvent(any(TradePaymentUnresolvedEvent.class));
    }

    @Test
    @DisplayName("RACE: the seller confirm committed between the job's id scan and the row lock - the row is SETTLED and stays SETTLED")
    void confirmedTradeIsNotOverwritten() {
        TradeExecution e = execution(SettlementStatus.SETTLED, Instant.now().minus(90, ChronoUnit.HOURS),
                Instant.now().minus(80, ChronoUnit.HOURS));

        assertThat(processor.timeoutAwaiting(executionId, cutoff)).isFalse();
        assertThat(processor.timeoutPending(executionId, cutoff)).isFalse();

        assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        verify(executions, never()).save(any());
        verify(listings, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("a trade that is not yet past the cutoff is left alone")
    void youngTradeIsSkipped() {
        execution(SettlementStatus.AWAITING_SELLER_CONFIRMATION, Instant.now(), Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(processor.timeoutAwaiting(executionId, cutoff)).isFalse();
    }
}
