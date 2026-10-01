package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import de.makibytes.registerwerk.webhook.events.WebhookSubscriptionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds and sends outbound webhook deliveries for curated events (F-BLOCKER-2, hardened in Phase 5).
 *
 * <p>Deliberately <em>not</em> transactional as a whole: the delivery row is persisted in its own short
 * transaction, the HTTP call runs with no transaction and no connection held, and the result is written
 * in a second short transaction. A slow or hanging receiver therefore never holds a DB transaction (or,
 * in the retry sweep, blocks other tenants' deliveries).
 *
 * <p>Retries follow {@code next_attempt_at} (exponential backoff 1m, 2m, 4m ... capped at 1h, jittered)
 * up to {@code max-attempts}; a subscription with {@code circuit-breaker-failures} consecutive failed
 * deliveries is disabled automatically.
 */
@Service
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    /** A claimed delivery and the receiver host, so the sweep can cap concurrency per host. */
    record DueDelivery(UUID deliveryId, String host) {}

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSigningService signingService;
    private final WebhookSecretCipher secretCipher;
    private final WebhookHttpSender sender;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate readOnly;

    WebhookDispatchService(WebhookSubscriptionRepository subscriptionRepository,
                           WebhookDeliveryRepository deliveryRepository,
                           WebhookSigningService signingService,
                           WebhookSecretCipher secretCipher,
                           WebhookHttpSender sender,
                           WebhookProperties properties,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager txManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.signingService = signingService;
        this.secretCipher = secretCipher;
        this.sender = sender;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    /** Fans out {@code payload} to every enabled subscription this entity has for {@code type}. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch(UUID entityId, WebhookEventType type, Map<String, Object> payload) {
        if (entityId == null) return;
        UUID eventId = UUID.randomUUID();
        for (WebhookSubscription sub : subscriptionRepository.findByEntityIdAndEnabledTrue(entityId)) {
            if (!sub.isSubscribedTo(type)) continue;
            try {
                UUID deliveryId = createDelivery(sub, type, eventId, payload);
                if (deliveryId != null) {
                    attempt(deliveryId);
                }
            } catch (RuntimeException e) {
                // one subscriber's failure must not stop delivery to the others
                log.error("Webhook dispatch failed: subscriptionId={} eventType={}", sub.getId(), type, e);
            }
        }
    }

    UUID createDelivery(WebhookSubscription subscription, WebhookEventType type, UUID eventId, Map<String, Object> data) {
        return requiresNew.execute(status -> {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setSubscriptionId(subscription.getId());
            delivery.setEventType(type);
            delivery.setEventId(eventId);
            // crash safety: if this node dies before the result is written, the sweep picks it up after the lease
            delivery.setNextAttemptAt(Instant.now().plusSeconds(properties.getLeaseSeconds()));
            delivery.setPayload("{}");
            WebhookDelivery saved = deliveryRepository.save(delivery);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", eventId.toString());
            envelope.put("deliveryId", saved.getId().toString());
            envelope.put("eventType", type.name());
            envelope.put("occurredAt", Instant.now().toString());
            envelope.put("data", data);
            try {
                saved.setPayload(objectMapper.writeValueAsString(envelope));
            } catch (Exception e) {
                log.error("Failed to serialize webhook payload: subscriptionId={} eventType={}", subscription.getId(), type, e);
                status.setRollbackOnly();
                return null;
            }
            return saved.getId();
        });
    }

    /** What one send needs, read in a short transaction so nothing is held during the HTTP call. */
    private record Prepared(UUID deliveryId, UUID subscriptionId, String url, String body,
                            WebhookEventType type, UUID eventId, List<String> secrets) {}

    /** One delivery attempt: read, send (no transaction), record. Safe to call for SUCCESS rows (no-op). */
    void attempt(UUID deliveryId) {
        Prepared prepared;
        try {
            prepared = readOnly.execute(status -> prepare(deliveryId));
        } catch (RuntimeException e) {
            // e.g. the secret cannot be decrypted (KEK changed/lost): count it as a failed attempt so the
            // delivery reaches max-attempts and the circuit breaker instead of being re-claimed forever
            log.warn("Webhook delivery could not be prepared: deliveryId={} error={}", deliveryId, e.getClass().getSimpleName());
            WebhookHttpSender.SendResult failed = new WebhookHttpSender.SendResult(WebhookDeliveryOutcome.UNREACHABLE, null);
            requiresNew.executeWithoutResult(status -> deliveryRepository.findById(deliveryId)
                    .ifPresent(d -> record(deliveryId, d.getSubscriptionId(), failed)));
            return;
        }
        if (prepared == null) return;

        long timestamp = Instant.now().getEpochSecond();
        WebhookHttpSender.SendResult result;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Registerwerk-Event", prepared.type().name());
            headers.put("X-Registerwerk-Event-Id", prepared.eventId().toString());
            headers.put("X-Registerwerk-Delivery", prepared.deliveryId().toString());
            headers.put("X-Registerwerk-Timestamp", Long.toString(timestamp));
            headers.put("X-Registerwerk-Signature", signingService.signatureHeader(
                    timestamp, prepared.deliveryId(), prepared.body(), prepared.secrets()));
            result = sender.post(prepared.url(), prepared.body(), headers);
        } catch (RuntimeException e) {
            log.warn("Webhook delivery could not be sent: deliveryId={} error={}", deliveryId, e.getClass().getSimpleName());
            result = new WebhookHttpSender.SendResult(WebhookDeliveryOutcome.UNREACHABLE, null);
        }
        WebhookHttpSender.SendResult finalResult = result;
        requiresNew.executeWithoutResult(status -> record(deliveryId, prepared.subscriptionId(), finalResult));
    }

    private Prepared prepare(UUID deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == WebhookDeliveryStatus.SUCCESS) return null;
        WebhookSubscription subscription = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (subscription == null || !subscription.isEnabled()) return null;
        List<String> secrets = new ArrayList<>();
        secrets.add(secretCipher.decrypt(subscription.getSecret(), subscription.getId()));
        Instant rotatedAt = subscription.getSecretRotatedAt();
        if (subscription.getSecretPreviousEnc() != null && rotatedAt != null
                && rotatedAt.plus(Duration.ofHours(properties.getRotationOverlapHours())).isAfter(Instant.now())) {
            secrets.add(secretCipher.decrypt(subscription.getSecretPreviousEnc(), subscription.getId()));
        }
        return new Prepared(delivery.getId(), subscription.getId(), subscription.getUrl(), delivery.getPayload(),
                delivery.getEventType(), delivery.getEventId(), secrets);
    }

    private void record(UUID deliveryId, UUID subscriptionId, WebhookHttpSender.SendResult result) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) return;
        boolean ok = result.outcome() == WebhookDeliveryOutcome.OK;
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptedAt(Instant.now());
        delivery.setOutcome(result.outcome());
        delivery.setResponseCode(result.httpStatus());
        if (ok) {
            delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
            delivery.setNextAttemptAt(null);
        } else {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(delivery.getAttemptCount() >= properties.getMaxAttempts()
                    ? null : Instant.now().plus(backoff(delivery.getAttemptCount())));
        }
        deliveryRepository.save(delivery);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null) return;
        if (ok) {
            if (subscription.getConsecutiveFailures() != 0) {
                subscription.setConsecutiveFailures(0);
                subscriptionRepository.save(subscription);
            }
        } else {
            subscription.setConsecutiveFailures(subscription.getConsecutiveFailures() + 1);
            if (subscription.isEnabled() && subscription.getConsecutiveFailures() >= properties.getCircuitBreakerFailures()) {
                subscription.setEnabled(false);
                subscription.setDisabledReason("CIRCUIT_BREAKER");
                log.warn("Webhook subscription auto-disabled after {} consecutive failures: id={}",
                        subscription.getConsecutiveFailures(), subscription.getId());
                eventPublisher.publishEvent(new WebhookSubscriptionChangedEvent(subscription.getId(),
                        subscription.getEntityId(), null, "SYSTEM", "AUTO_DISABLED",
                        Map.of("reason", "CIRCUIT_BREAKER")));
            }
            subscriptionRepository.save(subscription);
        }
    }

    /** 1m, 2m, 4m ... capped at 1h, +-20% jitter. */
    Duration backoff(int attemptCount) {
        long seconds = Math.min(3600L, 60L * (1L << Math.min(Math.max(attemptCount - 1, 0), 10)));
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (seconds * 1000 * jitter));
    }

    // ── retry sweep support ─────────────────────────────────────────────────────────────────────

    /** Claims due deliveries and leases them (short transaction, SKIP LOCKED). */
    List<DueDelivery> claimDue() {
        return requiresNew.execute(status -> {
            Instant now = Instant.now();
            List<WebhookDelivery> due = deliveryRepository.claimDue(now, properties.getSweepBatchSize());
            List<DueDelivery> out = new ArrayList<>(due.size());
            for (WebhookDelivery delivery : due) {
                delivery.setNextAttemptAt(now.plusSeconds(properties.getLeaseSeconds()));
                deliveryRepository.save(delivery);
                String host = subscriptionRepository.findById(delivery.getSubscriptionId())
                        .map(s -> hostOf(s.getUrl())).orElse("");
                out.add(new DueDelivery(delivery.getId(), host));
            }
            return out;
        });
    }

    /** Puts a claimed delivery back (e.g. per-host cap reached) so it is retried shortly. */
    void defer(UUID deliveryId, Duration delay) {
        requiresNew.executeWithoutResult(status -> deliveryRepository.findById(deliveryId).ifPresent(d -> {
            d.setNextAttemptAt(Instant.now().plus(delay));
            deliveryRepository.save(d);
        }));
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
