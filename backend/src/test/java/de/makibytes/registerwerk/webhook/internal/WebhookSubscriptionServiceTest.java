package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import de.makibytes.registerwerk.webhook.events.WebhookSubscriptionChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookSubscriptionService unit tests")
class WebhookSubscriptionServiceTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final WebhookSecretCipher cipher = new WebhookSecretCipher(WebhookSecretCipherTest.XOR_KEK);
    private WebhookSigningService signingService;
    private WebhookSubscriptionService service;

    private final UUID entityId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        signingService = new WebhookSigningService();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new WebhookProperties(), false, host -> {
            try {
                return InetAddress.getAllByName(host.equals("example.com") ? "93.184.216.34" : host);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        service = new WebhookSubscriptionService(subscriptionRepository, deliveryRepository, signingService,
                cipher, policy, eventPublisher);
        lenient().when(subscriptionRepository.save(any(WebhookSubscription.class))).thenAnswer(inv -> {
            WebhookSubscription s = inv.getArgument(0);
            if (s.getId() == null) ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            return s;
        });
    }

    private WebhookSubscription owned() {
        WebhookSubscription s = new WebhookSubscription();
        ReflectionTestUtils.setField(s, "id", subscriptionId);
        s.setEntityId(entityId);
        s.setUrl("https://example.com/hook");
        s.setSecret(cipher.encrypt("current", subscriptionId));
        return s;
    }

    @Test
    @DisplayName("create stores the secret encrypted (enc:v1:), returns the plaintext once, and audits")
    void create_encryptsSecretAtRest() {
        var result = service.create(entityId, "https://example.com/hook", Set.of(WebhookEventType.TRADE_EXECUTED), UUID.randomUUID());

        assertThat(result.secret()).isNotBlank();
        assertThat(result.subscription().getSecret()).startsWith("enc:v1:").doesNotContain(result.secret());
        assertThat(cipher.decrypt(result.subscription().getSecret(), result.subscription().getId())).isEqualTo(result.secret());
        assertThat(result.subscription().getEventTypes()).containsExactly(WebhookEventType.TRADE_EXECUTED);
        verify(eventPublisher).publishEvent(any(WebhookSubscriptionChangedEvent.class));
    }

    @Test
    @DisplayName("create refuses cloud-metadata, private and non-https URLs (400) and stores nothing")
    void create_rejectsUnsafeUrls() {
        for (String url : List.of("http://169.254.169.254/", "https://169.254.169.254/latest", "https://10.0.0.5/x",
                "https://[::ffff:127.0.0.1]/x", "http://example.com/hook", "https://example.com:8080/hook")) {
            assertThatThrownBy(() -> service.create(entityId, url, Set.of(), UUID.randomUUID()))
                    .as(url).isInstanceOf(IllegalArgumentException.class);
        }
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("rotateSecret keeps the previous secret for the overlap and returns the new plaintext once")
    void rotateSecret() {
        WebhookSubscription sub = owned();
        String before = sub.getSecret();
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));

        var result = service.rotateSecret(entityId, subscriptionId, UUID.randomUUID());

        assertThat(sub.getSecretPreviousEnc()).isEqualTo(before);
        assertThat(cipher.decrypt(sub.getSecretPreviousEnc(), subscriptionId)).isEqualTo("current");
        assertThat(cipher.decrypt(sub.getSecret(), subscriptionId)).isEqualTo(result.secret());
        assertThat(sub.getSecretRotatedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(WebhookSubscriptionChangedEvent.class));
    }

    @Test
    @DisplayName("rotateSecret throws for a subscription of another entity")
    void rotateSecret_notOwned() {
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotateSecret(entityId, subscriptionId, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("listForEntity delegates to the repository")
    void listForEntity_delegates() {
        WebhookSubscription sub = owned();
        when(subscriptionRepository.findByEntityIdOrderByCreatedAtDesc(entityId)).thenReturn(List.of(sub));
        assertThat(service.listForEntity(entityId)).containsExactly(sub);
    }

    @Test
    @DisplayName("disabling updates an owned subscription; re-enabling re-validates the URL and clears the platform disable reason")
    void setEnabled_ownedSubscription_updates() {
        WebhookSubscription sub = owned();
        sub.setEnabled(false);
        sub.setDisabledReason("CIRCUIT_BREAKER");
        sub.setConsecutiveFailures(20);
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));

        service.setEnabled(entityId, subscriptionId, true, UUID.randomUUID());

        assertThat(sub.isEnabled()).isTrue();
        assertThat(sub.getDisabledReason()).isNull();
        assertThat(sub.getConsecutiveFailures()).isZero();

        service.setEnabled(entityId, subscriptionId, false, UUID.randomUUID());
        assertThat(sub.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("re-enabling a subscription whose URL now violates the policy is refused")
    void setEnabled_badUrl_refused() {
        WebhookSubscription sub = owned();
        sub.setUrl("https://10.0.0.5/hook");
        sub.setEnabled(false);
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.setEnabled(entityId, subscriptionId, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sub.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("setEnabled throws for a subscription belonging to a different entity")
    void setEnabled_notOwned_throws() {
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setEnabled(entityId, subscriptionId, false, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("delete removes only a subscription owned by the calling entity")
    void delete_ownedSubscription_deletes() {
        WebhookSubscription sub = owned();
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(sub));
        service.delete(entityId, subscriptionId, UUID.randomUUID());
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
        when(subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)).thenReturn(Optional.of(owned()));
        WebhookDelivery delivery = new WebhookDelivery();
        when(deliveryRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId)).thenReturn(List.of(delivery));
        assertThat(service.listDeliveries(entityId, subscriptionId)).containsExactly(delivery);
    }
}
