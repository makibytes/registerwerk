package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import de.makibytes.registerwerk.webhook.events.WebhookSubscriptionChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookDispatchService — envelope, signing, retry state, circuit breaker (5D-08/5D-09)")
class WebhookDispatchServiceTest {

    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookHttpSender sender;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final WebhookSigningService signingService = new WebhookSigningService();
    private final WebhookSecretCipher cipher = new WebhookSecretCipher(WebhookSecretCipherTest.XOR_KEK);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookProperties props = new WebhookProperties();
    private final Map<UUID, WebhookDelivery> deliveries = new HashMap<>();

    private WebhookDispatchService service;
    private WebhookSubscription subscription;
    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WebhookDispatchService(subscriptionRepository, deliveryRepository, signingService, cipher,
                sender, props, objectMapper, eventPublisher, new NoopTransactionManager());
        subscription = new WebhookSubscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setEntityId(entityId);
        subscription.setUrl("https://hooks.example.com/in");
        subscription.setSecret(cipher.encrypt("current-secret", subscription.getId()));

        lenient().when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(subscription));
        lenient().when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        lenient().when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            deliveries.put(d.getId(), d);
            return d;
        });
        lenient().when(deliveryRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(deliveries.get(inv.<UUID>getArgument(0))));
    }

    private WebhookHttpSender.SendResult ok() {
        return new WebhookHttpSender.SendResult(WebhookDeliveryOutcome.OK, 200);
    }

    private WebhookHttpSender.SendResult fail() {
        return new WebhookHttpSender.SendResult(WebhookDeliveryOutcome.RECEIVER_ERROR, 503);
    }

    @Test
    @DisplayName("envelope carries eventId + deliveryId; headers carry delivery id, timestamp and a v1 signature that verifies")
    void envelopeAndHeaders() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(sender.post(anyString(), body.capture(), headers.capture())).thenReturn(ok());

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of("entityId", entityId.toString()));

        JsonNode json = objectMapper.readTree(body.getValue());
        UUID deliveryId = UUID.fromString(json.get("deliveryId").asString());
        assertThat(json.get("eventId").asString()).isNotBlank();
        assertThat(json.get("eventType").asString()).isEqualTo("KYC_APPROVED");
        assertThat(json.has("data")).isTrue();

        Map<String, String> h = headers.getValue();
        assertThat(h.get("X-Registerwerk-Delivery")).isEqualTo(deliveryId.toString());
        assertThat(h.get("X-Registerwerk-Event-Id")).isEqualTo(json.get("eventId").asString());
        long ts = Long.parseLong(h.get("X-Registerwerk-Timestamp"));
        assertThat(ts).isBetween(Instant.now().getEpochSecond() - 5, Instant.now().getEpochSecond() + 5);
        assertThat(h.get("X-Registerwerk-Signature"))
                .isEqualTo("v1=" + signingService.sign(ts, deliveryId, body.getValue(), "current-secret"));
        assertThat(h).doesNotContainKey("X-Registerwerk-Signature-Legacy");

        WebhookDelivery stored = deliveries.get(deliveryId);
        assertThat(stored.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(stored.getOutcome()).isEqualTo(WebhookDeliveryOutcome.OK);
        assertThat(stored.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("retry keeps the delivery id and body but refreshes the timestamp and signature")
    void retryKeepsDeliveryIdRefreshesTimestamp() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(sender.post(anyString(), body.capture(), headers.capture())).thenReturn(fail()).thenReturn(ok());

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of("k", "v"));
        UUID deliveryId = deliveries.keySet().iterator().next();
        Thread.sleep(1100);
        service.attempt(deliveryId);

        List<Map<String, String>> sent = headers.getAllValues();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).get("X-Registerwerk-Delivery")).isEqualTo(sent.get(0).get("X-Registerwerk-Delivery"));
        assertThat(body.getAllValues().get(1)).isEqualTo(body.getAllValues().get(0));
        assertThat(sent.get(1).get("X-Registerwerk-Timestamp")).isNotEqualTo(sent.get(0).get("X-Registerwerk-Timestamp"));
        assertThat(sent.get(1).get("X-Registerwerk-Signature")).isNotEqualTo(sent.get(0).get("X-Registerwerk-Signature"));
        assertThat(deliveries.get(deliveryId).getAttemptCount()).isEqualTo(2);
        assertThat(deliveries.get(deliveryId).getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
    }

    @Test
    @DisplayName("one event fans out with the same eventId but different deliveryIds")
    void eventIdSharedAcrossSubscribers() throws Exception {
        WebhookSubscription second = new WebhookSubscription();
        ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        second.setEntityId(entityId);
        second.setUrl("https://other.example.com/in");
        second.setSecret(cipher.encrypt("other", second.getId()));
        when(subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)).thenReturn(List.of(subscription, second));
        lenient().when(subscriptionRepository.findById(second.getId())).thenReturn(Optional.of(second));
        when(sender.post(anyString(), anyString(), anyMap())).thenReturn(ok());

        service.dispatch(entityId, WebhookEventType.TRADE_EXECUTED, Map.of());

        assertThat(deliveries.values()).hasSize(2);
        assertThat(deliveries.values().stream().map(WebhookDelivery::getEventId).distinct()).hasSize(1);
        assertThat(deliveries.keySet()).hasSize(2);
    }

    @Test
    @DisplayName("during the rotation overlap the header carries a signature for both secrets; afterwards only the new one")
    void rotationOverlap() {
        subscription.setSecretPreviousEnc(cipher.encrypt("old-secret", subscription.getId()));
        subscription.setSecretRotatedAt(Instant.now().minusSeconds(3600));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(sender.post(anyString(), body.capture(), headers.capture())).thenReturn(ok());

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());

        Map<String, String> h = headers.getValue();
        UUID deliveryId = UUID.fromString(h.get("X-Registerwerk-Delivery"));
        long ts = Long.parseLong(h.get("X-Registerwerk-Timestamp"));
        assertThat(h.get("X-Registerwerk-Signature").split(",")).containsExactlyInAnyOrder(
                "v1=" + signingService.sign(ts, deliveryId, body.getValue(), "current-secret"),
                "v1=" + signingService.sign(ts, deliveryId, body.getValue(), "old-secret"));

        // overlap expired
        subscription.setSecretRotatedAt(Instant.now().minusSeconds(props.getRotationOverlapHours() * 3600L + 60));
        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        assertThat(headers.getValue().get("X-Registerwerk-Signature")).doesNotContain(",");
    }

    @Test
    @DisplayName("Wave 5b: a plaintext secret column value is refused - no delivery is signed with it")
    void plaintextSecretIsRefused() {
        subscription.setSecret("legacy-plain");

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());

        WebhookDelivery delivery = deliveries.values().iterator().next();
        assertThat(delivery.getOutcome()).isEqualTo(WebhookDeliveryOutcome.UNREACHABLE);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(sender, never()).post(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("failure schedules a backed-off retry; after max attempts no further retry is scheduled")
    void failureSchedulesRetryThenStops() {
        when(sender.post(anyString(), anyString(), anyMap())).thenReturn(fail());

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        WebhookDelivery d = deliveries.values().iterator().next();

        assertThat(d.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(d.getOutcome()).isEqualTo(WebhookDeliveryOutcome.RECEIVER_ERROR);
        assertThat(d.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(30)).isBefore(Instant.now().plusSeconds(120));

        d.setAttemptCount(props.getMaxAttempts() - 1);
        service.attempt(d.getId());
        assertThat(d.getAttemptCount()).isEqualTo(props.getMaxAttempts());
        assertThat(d.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("backoff doubles from ~1 minute and is capped at ~1 hour")
    void backoffShape() {
        assertThat(service.backoff(1).toSeconds()).isBetween(48L, 72L);
        assertThat(service.backoff(3).toSeconds()).isBetween(192L, 288L);
        assertThat(service.backoff(20).toSeconds()).isBetween(2880L, 4320L);
    }

    @Test
    @DisplayName("circuit breaker: after N consecutive failures the subscription is disabled with a reason and an audit event")
    void circuitBreaker() {
        props.setCircuitBreakerFailures(3);
        when(sender.post(anyString(), anyString(), anyMap())).thenReturn(fail());

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        assertThat(subscription.isEnabled()).isTrue();
        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());

        assertThat(subscription.isEnabled()).isFalse();
        assertThat(subscription.getDisabledReason()).isEqualTo("CIRCUIT_BREAKER");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(WebhookSubscriptionChangedEvent.class);
    }

    @Test
    @DisplayName("a success resets the consecutive-failure counter")
    void successResetsCounter() {
        subscription.setConsecutiveFailures(5);
        when(sender.post(anyString(), anyString(), anyMap())).thenReturn(ok());
        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        assertThat(subscription.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a disabled subscription or an already delivered row is not sent")
    void noSendWhenDisabledOrDelivered() {
        service.dispatch(UUID.randomUUID(), WebhookEventType.KYC_APPROVED, Map.of());
        WebhookDelivery done = new WebhookDelivery();
        ReflectionTestUtils.setField(done, "id", UUID.randomUUID());
        done.setSubscriptionId(subscription.getId());
        done.setStatus(WebhookDeliveryStatus.SUCCESS);
        deliveries.put(done.getId(), done);
        service.attempt(done.getId());

        subscription.setEnabled(false);
        WebhookDelivery pending = new WebhookDelivery();
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        pending.setSubscriptionId(subscription.getId());
        deliveries.put(pending.getId(), pending);
        service.attempt(pending.getId());

        verify(sender, never()).post(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("an event with a null entity is ignored; sender crash is recorded as UNREACHABLE")
    void senderCrashIsUnreachable() {
        when(sender.post(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("boom"));
        service.dispatch(null, WebhookEventType.KYC_APPROVED, Map.of());
        assertThat(deliveries).isEmpty();

        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        assertThat(deliveries.values().iterator().next().getOutcome()).isEqualTo(WebhookDeliveryOutcome.UNREACHABLE);
        verify(sender, times(1)).post(eq("https://hooks.example.com/in"), anyString(), anyMap());
    }

    @Test
    @DisplayName("N1: an undecryptable secret counts as a failed attempt and reaches max-attempts instead of looping")
    void decryptFailureCountsAsAttempt() {
        subscription.setSecret("enc:v1:not-decryptable");
        service.dispatch(entityId, WebhookEventType.KYC_APPROVED, Map.of());
        WebhookDelivery delivery = deliveries.values().iterator().next();
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getOutcome()).isEqualTo(WebhookDeliveryOutcome.UNREACHABLE);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(subscription.getConsecutiveFailures()).isEqualTo(1);
        for (int i = 1; i < props.getMaxAttempts(); i++) {
            service.attempt(delivery.getId());
        }
        assertThat(delivery.getAttemptCount()).isEqualTo(props.getMaxAttempts());
        assertThat(delivery.getNextAttemptAt()).isNull();
        verify(sender, never()).post(anyString(), anyString(), anyMap());
    }
}
