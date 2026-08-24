package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookSubscriptionService unit tests (Track 6-1)")
class WebhookSubscriptionServiceTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookSigningService signingService;

    private WebhookSubscriptionService service;

    private final UUID entityId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WebhookSubscriptionService(subscriptionRepository, deliveryRepository, signingService);
        lenient().when(subscriptionRepository.save(any(WebhookSubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(signingService.generateSecret()).thenReturn("generated-secret");
    }

    private WebhookSubscription owned() {
        WebhookSubscription s = new WebhookSubscription();
        s.setEntityId(entityId);
        return s;
    }

    @Test
    @DisplayName("create generates a secret and stores the given event types")
    void create_generatesSecretAndStoresEventTypes() {
        WebhookSubscription result = service.create(
                entityId, "https://example.com/hook", Set.of(WebhookEventType.TRADE_EXECUTED), UUID.randomUUID());

        assertThat(result.getSecret()).isEqualTo("generated-secret");
        assertThat(result.getEntityId()).isEqualTo(entityId);
        assertThat(result.getEventTypes()).containsExactly(WebhookEventType.TRADE_EXECUTED);
    }

    @Test
    @DisplayName("listForEntity delegates to the repository")
    void listForEntity_delegates() {
        WebhookSubscription sub = owned();
        when(subscriptionRepository.findByEntityIdOrderByCreatedAtDesc(entityId)).thenReturn(List.of(sub));

        assertThat(service.listForEntity(entityId)).containsExactly(sub);
    }

    @Test
    @DisplayName("setEnabled updates only a subscription owned by the calling entity")
    void setEnabled_ownedSubscription_updates() {
        WebhookSubscription sub = owned();
        sub.setEnabled(true);
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));

        service.setEnabled(entityId, subscriptionId, false);

        assertThat(sub.isEnabled()).isFalse();
        verify(subscriptionRepository).save(sub);
    }

    @Test
    @DisplayName("setEnabled throws for a subscription belonging to a different entity")
    void setEnabled_notOwned_throws() {
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setEnabled(entityId, subscriptionId, false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("delete removes only a subscription owned by the calling entity")
    void delete_ownedSubscription_deletes() {
        WebhookSubscription sub = owned();
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));

        service.delete(entityId, subscriptionId);

        verify(subscriptionRepository).delete(sub);
    }

    @Test
    @DisplayName("listDeliveries throws for a subscription belonging to a different entity, without leaking delivery data")
    void listDeliveries_notOwned_throws() {
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listDeliveries(entityId, subscriptionId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("listDeliveries returns the delivery log for an owned subscription")
    void listDeliveries_owned_returnsLog() {
        WebhookSubscription sub = owned();
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));
        WebhookDelivery delivery = new WebhookDelivery();
        when(deliveryRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId)).thenReturn(List.of(delivery));

        assertThat(service.listDeliveries(entityId, subscriptionId)).containsExactly(delivery);
    }
}
