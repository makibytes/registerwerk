package de.makibytes.registerwerk.webhook.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /**
     * Claims due deliveries for the retry sweep: PENDING/FAILED rows whose {@code next_attempt_at}
     * has passed, locked with {@code SKIP LOCKED} so concurrent nodes never claim the same row. The
     * caller must move {@code next_attempt_at} forward (a lease) inside the same transaction; the
     * HTTP call itself happens after commit.
     */
    @Query(value = """
            SELECT d.* FROM webhook_delivery d
              JOIN webhook_subscription s ON s.id = d.subscription_id
             WHERE d.status IN ('PENDING', 'FAILED')
               AND s.enabled = TRUE
               AND d.next_attempt_at IS NOT NULL AND d.next_attempt_at <= :now
             ORDER BY d.next_attempt_at
             LIMIT :limit
             FOR UPDATE OF d SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
