package de.makibytes.registerwerk.accessreview.web.dto;

import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code decision} is one of PENDING, CONFIRMED, REVOKED, REVOKE_PROPOSED (waiting for a second
 * reviewer) or STALE (roles changed since the snapshot: re-open it). {@code sodConflicts} is a
 * comma-separated list of role pairs ("A+B") held together — a warning, not a block.
 */
public record ItemResponse(
        UUID id, UUID campaignId, UUID appUserId, String email, String fullName, String roles,
        String decision, UUID reviewedBy, Instant reviewedAt, String notes,
        UUID proposedBy, Instant proposedAt, String sodConflicts, int reopenedCount
) {
    public static ItemResponse from(AccessReviewItem i) {
        return new ItemResponse(i.getId(), i.getCampaignId(), i.getAppUserId(), i.getEmailSnapshot(),
                i.getFullNameSnapshot(), i.getRolesSnapshot(), i.getDecision().name(),
                i.getReviewedBy(), i.getReviewedAt(), i.getNotes(),
                i.getProposedBy(), i.getProposedAt(), i.getSodConflicts(), i.getReopenedCount());
    }
}
