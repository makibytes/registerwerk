package de.makibytes.registerwerk.accessreview.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccessReviewCampaignRepository extends JpaRepository<AccessReviewCampaign, UUID> {

    List<AccessReviewCampaign> findAllByOrderByStartedAtDesc();
}
