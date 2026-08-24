package de.makibytes.registerwerk.webhook.web.dto;

import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** {@code eventTypes} empty/null subscribes to every curated event type. */
public record CreateWebhookSubscriptionRequest(@NotBlank String url, Set<WebhookEventType> eventTypes) {}
