package de.makibytes.registerwerk.accessreview.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

public record AccessReviewCampaignClosedEvent(UUID campaignId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "ACCESS_REVIEW_CAMPAIGN_CLOSED"; }
    public String subjectType() { return "AccessReviewCampaign"; }
    public UUID   subjectId()   { return campaignId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
