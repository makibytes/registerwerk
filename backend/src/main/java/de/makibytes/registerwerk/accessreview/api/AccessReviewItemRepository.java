package de.makibytes.registerwerk.accessreview.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessReviewItemRepository extends JpaRepository<AccessReviewItem, UUID> {

    List<AccessReviewItem> findByCampaignIdOrderByEmailSnapshotAsc(UUID campaignId);

    long countByCampaignIdAndDecision(UUID campaignId, AccessReviewDecision decision);

    Optional<AccessReviewItem> findByCampaignIdAndId(UUID campaignId, UUID itemId);

    /** Most recent item for this account across any campaign, regardless of decision — backs
     *  a "last reviewed" indicator on the user list without a dedicated denormalized column. */
    Optional<AccessReviewItem> findFirstByAppUserIdAndDecisionNotOrderByReviewedAtDesc(
            UUID appUserId, AccessReviewDecision decision);
}
