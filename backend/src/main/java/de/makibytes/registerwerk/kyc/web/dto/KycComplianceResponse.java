package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.shared.web.DocumentStatusResponse;

import java.util.List;
import java.util.UUID;

/**
 * Full KYC compliance result for an entity against a specific jurisdiction.
 */
public record KycComplianceResponse(
    String jurisdiction,
    String jurisdictionDisplayName,
    UUID entityId,
    List<DocumentStatusResponse> documents,
    boolean fullyCompliant,
    int missingCount,
    int expiredCount,
    int tooOldCount
) {
    public static KycComplianceResponse from(KycComplianceService.ComplianceResult r) {
        var docs = r.documents().stream().map(d -> new DocumentStatusResponse(
            d.documentType().name(), d.mandatory(), d.localName(), d.description(),
            d.present(), d.expired(), d.tooOld(), d.documentDate(), d.documentId()
        )).toList();
        return new KycComplianceResponse(
            r.jurisdiction().name(), r.jurisdiction().displayName,
            r.entityId(), docs, r.fullyCompliant(), r.missingCount(), r.expiredCount(), r.tooOldCount()
        );
    }
}
