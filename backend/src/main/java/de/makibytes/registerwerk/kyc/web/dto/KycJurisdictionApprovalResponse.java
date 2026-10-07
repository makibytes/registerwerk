package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for a per-jurisdiction KYC approval record.
 */
public record KycJurisdictionApprovalResponse(
    UUID id,
    UUID entityId,
    String jurisdiction,
    String jurisdictionDisplayName,
    String status,
    UUID approvedBy,
    Instant approvedAt,
    LocalDate expiresAt,
    String rejectionReason,
    String overrideNote
) {
    public static KycJurisdictionApprovalResponse from(KycJurisdictionApproval a) {
        return new KycJurisdictionApprovalResponse(
            a.getId(), a.getEntityId(), a.getJurisdiction().name(),
            a.getJurisdiction().displayName, a.getStatus().name(),
            a.getApprovedBy(), a.getApprovedAt(), a.getExpiresAt(),
            a.getRejectionReason(), a.getOverrideNote());
    }
}
