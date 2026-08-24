package de.makibytes.registerwerk.webhook.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /** Input for the retry job — never-succeeded deliveries, oldest first. */
    List<WebhookDelivery> findByStatusOrderByCreatedAtAsc(WebhookDeliveryStatus status);
}
