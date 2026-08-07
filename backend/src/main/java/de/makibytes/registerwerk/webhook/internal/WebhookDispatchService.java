package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and sends outbound webhook deliveries for curated events (F-BLOCKER-2). Delivery is
 * attempted synchronously at dispatch time (the caller is an {@code @ApplicationModuleListener},
 * already running after the originating transaction committed and off the request thread) and
 * retried later by {@code WebhookRetryJob} on failure.
 */
@Service
@Transactional
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    /** After this many attempts a delivery stops being retried automatically. */
    static final int MAX_ATTEMPTS = 8;

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSigningService signingService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    WebhookDispatchService(WebhookSubscriptionRepository subscriptionRepository,
                            WebhookDeliveryRepository deliveryRepository,
                            WebhookSigningService signingService,
                            ObjectMapper objectMapper,
                            RestClient.Builder restClientBuilder) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.signingService = signingService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    /** Fans out {@code payload} to every enabled subscription this entity has for {@code type}. */
    public void dispatch(UUID entityId, WebhookEventType type, Map<String, Object> payload) {
        if (entityId == null) return;
        subscriptionRepository.findByEntityIdAndEnabledTrue(entityId).stream()
                .filter(sub -> sub.isSubscribedTo(type))
                .forEach(sub -> dispatchTo(sub, type, payload));
    }

    private void dispatchTo(WebhookSubscription subscription, WebhookEventType type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", type.name());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("data", payload);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload: subscriptionId={} eventType={}", subscription.getId(), type, e);
            return;
        }

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setSubscriptionId(subscription.getId());
        delivery.setEventType(type);
        delivery.setPayload(body);
        WebhookDelivery saved = deliveryRepository.save(delivery);

        attempt(saved, subscription);
    }

    /** One delivery attempt — used both for the initial send and by the retry job. */
    void attempt(WebhookDelivery delivery, WebhookSubscription subscription) {
        String signature = signingService.sign(delivery.getPayload(), subscription.getSecret());
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptedAt(Instant.now());
        try {
            var response = restClient.post()
                    .uri(subscription.getUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Registerwerk-Signature", signature)
                    .header("X-Registerwerk-Event", delivery.getEventType().name())
                    .body(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity();
            delivery.setResponseCode(response.getStatusCode().value());
            delivery.setStatus(response.getStatusCode().is2xxSuccessful()
                    ? WebhookDeliveryStatus.SUCCESS : WebhookDeliveryStatus.FAILED);
        } catch (Exception e) {
            log.warn("Webhook delivery failed: subscriptionId={} eventType={} attempt={} error={}",
                    subscription.getId(), delivery.getEventType(), delivery.getAttemptCount(), e.getMessage());
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
        }
        deliveryRepository.save(delivery);
    }
}
