package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.internal.KycEvidenceService;

import java.math.BigDecimal;

/** Beneficial-owner coverage: {@code unexplainedPct} is 100 minus the identified share. */
public record OwnershipSummaryResponse(
        int activeCount,
        BigDecimal identifiedPct,
        BigDecimal unexplainedPct,
        boolean smoFallback,
        boolean coverageSufficient,
        BigDecimal requiredIdentifiedPct
) {
    public static OwnershipSummaryResponse from(KycEvidenceService.OwnershipSummary s) {
        return new OwnershipSummaryResponse(s.activeCount(), s.identifiedPct(), s.unexplainedPct(), s.smoFallback(),
                s.coverageSufficient(), KycEvidenceService.MIN_IDENTIFIED_PCT);
    }
}
