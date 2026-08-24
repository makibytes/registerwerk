package de.makibytes.registerwerk.webhook.web.dto;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponse(
        UUID id,
        WebhookEventType eventType,
        WebhookDeliveryStatus status,
        Integer responseCode,
        int attemptCount,
        Instant lastAttemptedAt,
        Instant createdAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(), d.getEventType(), d.getStatus(), d.getResponseCode(),
                d.getAttemptCount(), d.getLastAttemptedAt(), d.getCreatedAt());
    }
}
