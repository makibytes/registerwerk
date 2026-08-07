package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSigningService signingService;

    WebhookSubscriptionService(WebhookSubscriptionRepository subscriptionRepository,
                                WebhookDeliveryRepository deliveryRepository,
                                WebhookSigningService signingService) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.signingService = signingService;
    }

    /** Returns the created subscription, whose {@code secret} is only ever available at this
     *  point — it is not stored anywhere retrievable in plaintext after this call returns. */
    public WebhookSubscription create(UUID entityId, String url, Set<WebhookEventType> eventTypes, UUID actorId) {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setEntityId(entityId);
        subscription.setUrl(url);
        subscription.setSecret(signingService.generateSecret());
        subscription.setEventTypes(eventTypes);
        subscription.setCreatedBy(actorId);
        WebhookSubscription saved = subscriptionRepository.save(subscription);
        log.info("Created webhook subscription: id={} entityId={}", saved.getId(), entityId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> listForEntity(UUID entityId) {
        return subscriptionRepository.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    public void setEnabled(UUID entityId, UUID subscriptionId, boolean enabled) {
        WebhookSubscription subscription = requireOwned(entityId, subscriptionId);
        subscription.setEnabled(enabled);
        subscriptionRepository.save(subscription);
    }

    public void delete(UUID entityId, UUID subscriptionId) {
        WebhookSubscription subscription = requireOwned(entityId, subscriptionId);
        subscriptionRepository.delete(subscription);
        log.info("Deleted webhook subscription: id={} entityId={}", subscriptionId, entityId);
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> listDeliveries(UUID entityId, UUID subscriptionId) {
        requireOwned(entityId, subscriptionId);
        return deliveryRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId);
    }

    private WebhookSubscription requireOwned(UUID entityId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndEntityId(subscriptionId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("WebhookSubscription", subscriptionId));
    }
}
