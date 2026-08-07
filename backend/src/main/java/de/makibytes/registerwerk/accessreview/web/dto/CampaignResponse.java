package de.makibytes.registerwerk.accessreview.web.dto;

import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaign;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CampaignResponse(
        UUID id, String name, String status, LocalDate dueDate,
        UUID startedBy, Instant startedAt, UUID closedBy, Instant closedAt
) {
    public static CampaignResponse from(AccessReviewCampaign c) {
        return new CampaignResponse(c.getId(), c.getName(), c.getStatus().name(), c.getDueDate(),
                c.getStartedBy(), c.getStartedAt(), c.getClosedBy(), c.getClosedAt());
    }
}
