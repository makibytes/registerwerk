package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;

import java.time.Instant;
import java.time.LocalDate;
import de.makibytes.registerwerk.kyc.events.KycRejectionCategory;
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
            customerVisibleReason(a.getRejectionReason()), a.getOverrideNote());
    }

    /**
     * The rejection reason is shown to the customer, so only a fixed {@link KycRejectionCategory} may leave the
     * platform. Rows written before that rule hold the operator's free text; those read as CONTACT_SUPPORT.
     */
    static String customerVisibleReason(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        for (KycRejectionCategory c : KycRejectionCategory.values()) {
            if (c.name().equals(stored)) {
                return stored;
            }
        }
        return KycRejectionCategory.CONTACT_SUPPORT.name();
    }
}
