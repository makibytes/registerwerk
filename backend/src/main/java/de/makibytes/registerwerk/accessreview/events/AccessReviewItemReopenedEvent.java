package de.makibytes.registerwerk.accessreview.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

public record AccessReviewItemReopenedEvent(UUID itemId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ACCESS_REVIEW_ITEM_REOPENED"; }
    public String subjectType() { return "AccessReviewItem"; }
    public UUID   subjectId()   { return itemId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
