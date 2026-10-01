package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.EddApproval;

import java.time.Instant;
import java.util.UUID;

public record EddApprovalResponse(UUID id, UUID naturalPersonId, UUID approvedBy, UUID secondApproverId,
                                  Instant reviewDue, Instant createdAt) {
    public static EddApprovalResponse from(EddApproval e) {
        return new EddApprovalResponse(e.getId(), e.getNaturalPersonId(), e.getApprovedBy(), e.getSecondApproverId(),
                e.getReviewDue(), e.getCreatedAt());
    }
}
