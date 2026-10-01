package de.makibytes.registerwerk.webhook.web.dto;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer-visible delivery record. Deliberately has no HTTP status code or error text: those would
 * turn the endpoint into a probe of internal addresses; {@code outcome} is coarse by design.
 * {@code id} is the delivery id sent in {@code X-Registerwerk-Delivery}; {@code eventId} is shared by
 * all subscribers of the same event.
 */
public record WebhookDeliveryResponse(
        UUID id,
        UUID eventId,
        WebhookEventType eventType,
        WebhookDeliveryStatus status,
        WebhookDeliveryOutcome outcome,
        int attemptCount,
        Instant lastAttemptedAt,
        Instant nextAttemptAt,
        Instant createdAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(), d.getEventId(), d.getEventType(), d.getStatus(), d.getOutcome(),
                d.getAttemptCount(), d.getLastAttemptedAt(), d.getNextAttemptAt(), d.getCreatedAt());
    }
}
