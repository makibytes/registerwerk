package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.internal.KycEvidenceService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An APPROVED entity that would not pass today's approval checks (existing approvals are not downgraded). */
public record EvidenceGapResponse(UUID entityId, String entityName, LocalDate kycExpiryDate, List<String> gaps) {
    public static EvidenceGapResponse from(KycEvidenceService.Gap g) {
        return new EvidenceGapResponse(g.entityId(), g.entityName(), g.kycExpiryDate(), g.gaps());
    }
}
