package de.makibytes.registerwerk.accessreview.web.dto;

import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;

import java.time.Instant;
import java.util.UUID;

public record ItemResponse(
        UUID id, UUID campaignId, UUID appUserId, String email, String fullName, String roles,
        String decision, UUID reviewedBy, Instant reviewedAt, String notes
) {
    public static ItemResponse from(AccessReviewItem i) {
        return new ItemResponse(i.getId(), i.getCampaignId(), i.getAppUserId(), i.getEmailSnapshot(),
                i.getFullNameSnapshot(), i.getRolesSnapshot(), i.getDecision().name(),
                i.getReviewedBy(), i.getReviewedAt(), i.getNotes());
    }
}
