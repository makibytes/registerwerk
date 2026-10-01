package de.makibytes.registerwerk.webhook.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Audit record for webhook subscription lifecycle changes (CREATED, ENABLED, DISABLED, DELETED,
 * SECRET_ROTATED, AUTO_DISABLED). Never carries the URL query string or any secret.
 */
public record WebhookSubscriptionChangedEvent(UUID subscriptionId, UUID entityId, UUID actorId,
                                              String actorRole, String action, Map<String, Object> details) implements AuditableEvent {

    public String eventType()   { return "WEBHOOK_SUBSCRIPTION_" + action; }
    public String subjectType() { return "WebhookSubscription"; }
    public UUID   subjectId()   { return subscriptionId; }
    public Map<String, Object> payload() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("entityId", entityId != null ? entityId.toString() : "");
        if (details != null) out.putAll(details);
        return out;
    }
}
