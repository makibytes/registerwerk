package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import de.makibytes.registerwerk.webhook.events.WebhookSubscriptionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    /** A subscription together with the plaintext secret — only ever returned by create / rotate. */
    public record WithSecret(WebhookSubscription subscription, String secret) {}

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSigningService signingService;
    private final WebhookSecretCipher secretCipher;
    private final OutboundUrlPolicy urlPolicy;
    private final ApplicationEventPublisher eventPublisher;

    WebhookSubscriptionService(WebhookSubscriptionRepository subscriptionRepository,
                                WebhookDeliveryRepository deliveryRepository,
                                WebhookSigningService signingService,
                                WebhookSecretCipher secretCipher,
                                OutboundUrlPolicy urlPolicy,
                                ApplicationEventPublisher eventPublisher) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.signingService = signingService;
        this.secretCipher = secretCipher;
        this.urlPolicy = urlPolicy;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates a subscription. The returned plaintext {@code secret} is available only from this call;
     * it is stored encrypted ({@code enc:v1:...}) and never returned again.
     *
     * @throws IllegalArgumentException (HTTP 400) if the URL fails the outbound URL policy
     */
    public WithSecret create(UUID entityId, String url, Set<WebhookEventType> eventTypes, UUID actorId) {
        String cleanUrl = url == null ? "" : url.trim();
        urlPolicy.requireAllowed(cleanUrl);
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setEntityId(entityId);
        subscription.setUrl(cleanUrl);
        subscription.setSecret("pending");
        subscription.setEventTypes(eventTypes);
        subscription.setCreatedBy(actorId);
        WebhookSubscription saved = subscriptionRepository.save(subscription);
        String plaintext = signingService.generateSecret();
        // the id (AAD) exists after persist; the INSERT is still deferred until commit
        saved.setSecret(secretCipher.encrypt(plaintext, saved.getId()));
        log.info("Created webhook subscription: id={} entityId={}", saved.getId(), entityId);
        audit(saved, actorId, "CREATED", Map.of("eventTypes", saved.getEventTypes().toString()));
        return new WithSecret(saved, plaintext);
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> listForEntity(UUID entityId) {
        return subscriptionRepository.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    public void setEnabled(UUID entityId, UUID subscriptionId, boolean enabled, UUID actorId) {
        WebhookSubscription subscription = requireOwned(entityId, subscriptionId);
        if (enabled) {
            urlPolicy.requireAllowed(subscription.getUrl());
            subscription.setDisabledReason(null);
            subscription.setConsecutiveFailures(0);
        }
        subscription.setEnabled(enabled);
        subscriptionRepository.save(subscription);
        audit(subscription, actorId, enabled ? "ENABLED" : "DISABLED", Map.of());
    }

    public void delete(UUID entityId, UUID subscriptionId, UUID actorId) {
        WebhookSubscription subscription = requireOwned(entityId, subscriptionId);
        subscriptionRepository.delete(subscription);
        log.info("Deleted webhook subscription: id={} entityId={}", subscriptionId, entityId);
        audit(subscription, actorId, "DELETED", Map.of());
    }

    /**
     * Issues a new signing secret. The old one stays valid for signing during the configured overlap
     * (deliveries carry two {@code v1=} signatures), so receivers can switch without dropping events.
     */
    public WithSecret rotateSecret(UUID entityId, UUID subscriptionId, UUID actorId) {
        WebhookSubscription subscription = requireOwned(entityId, subscriptionId);
        String plaintext = signingService.generateSecret();
        subscription.setSecretPreviousEnc(subscription.getSecret());
        subscription.setSecretRotatedAt(Instant.now());
        subscription.setSecret(secretCipher.encrypt(plaintext, subscription.getId()));
        subscriptionRepository.save(subscription);
        audit(subscription, actorId, "SECRET_ROTATED", Map.of());
        return new WithSecret(subscription, plaintext);
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> listDeliveries(UUID entityId, UUID subscriptionId) {
        requireOwned(entityId, subscriptionId);
        return deliveryRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId);
    }

    private void audit(WebhookSubscription s, UUID actorId, String action, Map<String, Object> details) {
        eventPublisher.publishEvent(new WebhookSubscriptionChangedEvent(
                s.getId(), s.getEntityId(), actorId, "COMPANY_ADMIN", action, details));
    }

    private WebhookSubscription requireOwned(UUID entityId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("WebhookSubscription", subscriptionId));
    }
}
