package de.makibytes.registerwerk.kyc.web.dto;

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
) {}
