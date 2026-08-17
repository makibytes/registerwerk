package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.events.TradeListingCancelledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerOffboardingTradingListener unit tests")
class CustomerOffboardingTradingListenerTest {

    @Mock private TradeListingRepository listingRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CustomerOffboardingTradingListener listener;

    @Test
    @DisplayName("cancels OPEN and PARTIALLY_FILLED listings but leaves already-terminal ones alone")
    void cancelsOnlyOpenListings() {
        UUID entityId = UUID.randomUUID();
        TradeListing open = new TradeListing();
        open.setStatus(ListingStatus.OPEN);
        TradeListing partiallyFilled = new TradeListing();
        partiallyFilled.setStatus(ListingStatus.PARTIALLY_FILLED);
        TradeListing alreadyFilled = new TradeListing();
        alreadyFilled.setStatus(ListingStatus.FILLED);

        when(listingRepository.findBySellerEntityIdOrderByCreatedAtDesc(entityId))
                .thenReturn(List.of(open, partiallyFilled, alreadyFilled));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        assertThat(open.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(partiallyFilled.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(alreadyFilled.getStatus()).isEqualTo(ListingStatus.FILLED); // untouched
        verify(listingRepository).save(open);
        verify(listingRepository).save(partiallyFilled);
        verify(listingRepository, never()).save(alreadyFilled);
    }

    @Test
    @DisplayName("publishes TradeListingCancelledEvent per cancelled listing ")
    void publishesEventPerCancelledListing() {
        UUID entityId = UUID.randomUUID();
        TradeListing open = new TradeListing();
        open.setStatus(ListingStatus.OPEN);
        open.setSellerEntityId(entityId);
        TradeListing partiallyFilled = new TradeListing();
        partiallyFilled.setStatus(ListingStatus.PARTIALLY_FILLED);
        partiallyFilled.setSellerEntityId(entityId);

        when(listingRepository.findBySellerEntityIdOrderByCreatedAtDesc(entityId))
                .thenReturn(List.of(open, partiallyFilled));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        ArgumentCaptor<TradeListingCancelledEvent> captor = ArgumentCaptor.forClass(TradeListingCancelledEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> assertThat(event.sellerEntityId()).isEqualTo(entityId));
    }

    @Test
    @DisplayName("does not publish an event when there is nothing to cancel")
    void doesNotPublishWhenNothingCancelled() {
        UUID entityId = UUID.randomUUID();
        when(listingRepository.findBySellerEntityIdOrderByCreatedAtDesc(entityId)).thenReturn(List.of());

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        verify(eventPublisher, never()).publishEvent(any());
    }
}
