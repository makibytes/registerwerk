package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.KycApprovalRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A previous entity-level KYC approval: who decided, the evidence state at the time and any override. */
public record KycDecisionResponse(
        UUID id,
        UUID approvedBy,
        UUID secondApproverId,
        String jurisdiction,
        LocalDate expiryDate,
        boolean checklistCompliant,
        String overrideNote,
        BigDecimal identifiedPct,
        boolean smoFallback,
        Instant createdAt
) {
    public static KycDecisionResponse from(KycApprovalRecord r) {
        return new KycDecisionResponse(r.getId(), r.getApprovedBy(), r.getSecondApproverId(),
                r.getJurisdiction() != null ? r.getJurisdiction().name() : null, r.getExpiryDate(),
                r.isChecklistCompliant(), r.getOverrideNote(), r.getIdentifiedPct(), r.isSmoFallback(),
                r.getCreatedAt());
    }
}
