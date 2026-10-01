package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.events.HolderRemovedEvent;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentUnresolvedEvent;
import de.makibytes.registerwerk.trading.events.TradePendingCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 5A-04 / 5A-05 / 5C-03: register removal, asset status and offboarding pull listings and trades off the market. */
@DisplayName("Trade invalidation listeners")
class TradeInvalidationServiceTest {

    private final UUID holderId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private TradeListingRepository listings;
    private TradeExecutionRepository executions;
    private ApplicationEventPublisher events;
    private HolderRemovedTradingListener holderListener;
    private CustomerOffboardingTradingListener offboardingListener;

    @BeforeEach
    void setUp() {
        listings = mock(TradeListingRepository.class);
        executions = mock(TradeExecutionRepository.class);
        events = mock(ApplicationEventPublisher.class);
        var transitions = new TradeTransitions(executions, listings, events, new TradingProperties());
        var service = new TradeInvalidationService(listings, executions, transitions, events);
        holderListener = new HolderRemovedTradingListener(service);
        offboardingListener = new CustomerOffboardingTradingListener(service);
    }

    private TradeListing listing(ListingStatus status) {
        TradeListing l = new TradeListing();
        ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
        l.setStatus(status);
        l.setSellerEntityId(UUID.randomUUID());
        l.setQuantityAvailable(BigDecimal.TEN);
        when(listings.findByIdForUpdate(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    private TradeExecution execution(SettlementStatus status, UUID listingId) {
        TradeExecution e = new TradeExecution();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setSettlementStatus(status);
        e.setListingId(listingId);
        e.setBuyerEntityId(UUID.randomUUID());
        e.setSellerEntityId(UUID.randomUUID());
        e.setExecutedQuantity(BigDecimal.valueOf(3));
        when(executions.findByIdForUpdate(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("removeHolder: open listings CANCELLED, PENDING trade cancelled, paid AWAITING trade -> PAYMENT_UNRESOLVED (never silently failed)")
    void holderRemovedCancelsListingsAndParksPaidTrades() {
        TradeListing open = listing(ListingStatus.OPEN);
        TradeListing filledButReserved = listing(ListingStatus.FILLED);
        TradeListing settledFilled = listing(ListingStatus.FILLED);
        when(listings.findBySellerHolderId(holderId)).thenReturn(List.of(open, filledButReserved, settledFilled));
        TradeExecution pending = execution(SettlementStatus.PENDING, open.getId());
        TradeExecution awaiting = execution(SettlementStatus.AWAITING_SELLER_CONFIRMATION, filledButReserved.getId());
        when(executions.countByListingIdAndSettlementStatusIn(eq(filledButReserved.getId()), any())).thenReturn(1L);
        when(executions.countByListingIdAndSettlementStatusIn(eq(settledFilled.getId()), any())).thenReturn(0L);
        when(executions.findBySellerHolderIdAndSettlementStatusIn(eq(holderId), any())).thenReturn(List.of(pending, awaiting));

        holderListener.onHolderRemoved(new HolderRemovedEvent(holderId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        assertThat(open.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(filledButReserved.getStatus()).isEqualTo(ListingStatus.CANCELLED); // a later release must not reopen it
        assertThat(settledFilled.getStatus()).isEqualTo(ListingStatus.FILLED);         // history untouched
        assertThat(pending.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(pending.getBuyerCooldownUntil()).isNull(); // not the buyer's fault
        assertThat(awaiting.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(awaiting.getUnresolvedReason()).contains("REGISTER_ENTRY_REMOVED");
        verify(events, times(2)).publishEvent(any(TradeListingCancelledEvent.class));
        verify(events).publishEvent(any(TradePendingCancelledEvent.class));
        verify(events).publishEvent(any(TradePaymentUnresolvedEvent.class));
    }

    @Test
    @DisplayName("the cancelled listing is not re-opened when the PENDING trade's quantity is restored")
    void restoredQuantityDoesNotReopenCancelledListing() {
        TradeListing filled = listing(ListingStatus.FILLED);
        when(listings.findBySellerHolderId(holderId)).thenReturn(List.of(filled));
        TradeExecution pending = execution(SettlementStatus.PENDING, filled.getId());
        when(executions.countByListingIdAndSettlementStatusIn(eq(filled.getId()), any())).thenReturn(1L);
        when(executions.findBySellerHolderIdAndSettlementStatusIn(eq(holderId), any())).thenReturn(List.of(pending));

        holderListener.onHolderRemoved(new HolderRemovedEvent(holderId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        assertThat(filled.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(filled.getQuantityAvailable()).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("asset SUSPENDED and REDEEMED both cancel listings of the asset and park paid trades")
    void assetSuspendedAndRedeemed() {
        TradeListing open = listing(ListingStatus.PARTIALLY_FILLED);
        when(listings.findByAssetId(assetId)).thenReturn(List.of(open));
        TradeExecution awaiting = execution(SettlementStatus.AWAITING_SELLER_CONFIRMATION, open.getId());
        when(executions.findByAssetIdAndSettlementStatusIn(eq(assetId), any())).thenReturn(List.of(awaiting));

        holderListener.onAssetSuspended(new AssetSuspendedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));
        assertThat(open.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(awaiting.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(awaiting.getUnresolvedReason()).contains("ASSET_SUSPENDED");

        TradeListing again = listing(ListingStatus.OPEN);
        when(listings.findByAssetId(assetId)).thenReturn(List.of(again));
        when(executions.findByAssetIdAndSettlementStatusIn(eq(assetId), any())).thenReturn(List.of());
        holderListener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));
        assertThat(again.getStatus()).isEqualTo(ListingStatus.CANCELLED);
    }

    @Test
    @DisplayName("offboarding: open listings cancelled (event per listing, sellerEntityId set) and in-flight trades handled")
    void offboardingCancelsListingsAndHandlesTrades() {
        UUID entityId = UUID.randomUUID();
        TradeListing open = listing(ListingStatus.OPEN);
        TradeListing partial = listing(ListingStatus.PARTIALLY_FILLED);
        TradeListing filled = listing(ListingStatus.FILLED);
        when(executions.countByListingIdAndSettlementStatusIn(eq(filled.getId()), any())).thenReturn(0L);
        when(listings.findBySellerEntityIdOrderByCreatedAtDesc(entityId)).thenReturn(List.of(open, partial, filled));
        TradeExecution asBuyerPending = execution(SettlementStatus.PENDING, UUID.randomUUID());
        TradeExecution asSellerAwaiting = execution(SettlementStatus.AWAITING_SELLER_CONFIRMATION, UUID.randomUUID());
        when(executions.findByPartyAndSettlementStatusIn(eq(entityId), any())).thenReturn(List.of(asBuyerPending, asSellerAwaiting));

        offboardingListener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        assertThat(open.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(partial.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(filled.getStatus()).isEqualTo(ListingStatus.FILLED);
        assertThat(asBuyerPending.getSettlementStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(asSellerAwaiting.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        verify(events, times(2)).publishEvent(any(TradeListingCancelledEvent.class));
    }

    @Test
    @DisplayName("an already PAYMENT_UNRESOLVED trade is left alone; nothing to do publishes nothing")
    void unresolvedIsLeftAloneAndNoopPublishesNothing() {
        when(listings.findBySellerHolderId(holderId)).thenReturn(List.of());
        TradeExecution unresolved = execution(SettlementStatus.PAYMENT_UNRESOLVED, UUID.randomUUID());
        when(executions.findBySellerHolderIdAndSettlementStatusIn(eq(holderId), any())).thenReturn(List.of(unresolved));

        holderListener.onHolderRemoved(new HolderRemovedEvent(holderId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        assertThat(unresolved.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        verify(events, never()).publishEvent(any(Object.class));
    }
}
