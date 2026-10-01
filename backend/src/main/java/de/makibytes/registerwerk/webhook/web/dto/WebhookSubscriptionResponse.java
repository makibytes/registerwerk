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
        /** Set when the platform (not the owner) disabled it: URL_POLICY or CIRCUIT_BREAKER. */
        String disabledReason,
        Instant secretRotatedAt,
        Instant createdAt,
        /** Only populated in the create / rotate-secret response — never re-shown afterward. */
        String secret
) {
    public static WebhookSubscriptionResponse from(WebhookSubscription s, String secretIfJustIssued) {
        return new WebhookSubscriptionResponse(
                s.getId(), s.getUrl(), s.getEventTypes(), s.isEnabled(), s.getDisabledReason(),
                s.getSecretRotatedAt(), s.getCreatedAt(), secretIfJustIssued);
    }

    public static WebhookSubscriptionResponse from(WebhookSubscription s) {
        return from(s, null);
    }
}
