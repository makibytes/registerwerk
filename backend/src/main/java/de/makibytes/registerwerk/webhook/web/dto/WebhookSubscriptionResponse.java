package de.makibytes.registerwerk.webhook.web.dto;

import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WebhookSubscriptionResponse(
        UUID id,
        String url,
        Set<WebhookEventType> eventTypes,
        boolean enabled,
        Instant createdAt,
        /** Only populated in the response to the create call — never re-shown afterward. */
        String secret
) {
    public static WebhookSubscriptionResponse from(WebhookSubscription s, String secretIfJustCreated) {
        return new WebhookSubscriptionResponse(
                s.getId(), s.getUrl(), s.getEventTypes(), s.isEnabled(), s.getCreatedAt(), secretIfJustCreated);
    }

    public static WebhookSubscriptionResponse from(WebhookSubscription s) {
        return from(s, null);
    }
}
