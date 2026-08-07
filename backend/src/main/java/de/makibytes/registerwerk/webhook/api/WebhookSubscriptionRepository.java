package de.makibytes.registerwerk.webhook.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByEntityIdOrderByCreatedAtDesc(UUID entityId);

    List<WebhookSubscription> findByEntityIdAndEnabledTrue(UUID entityId);

    Optional<WebhookSubscription> findByIdAndEntityId(UUID id, UUID entityId);
}
