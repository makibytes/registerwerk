package de.makibytes.registerwerk.kyc.web.dto;

import java.time.LocalDate;

/**
 * Request body for approving jurisdiction-specific KYC.
 *
 * @param expiresAt    optional KYC approval expiry date; defaults to now + 1 year if omitted
 * @param overrideNote required when approving despite missing/expired compliance requirements
 */
public record JurisdictionApprovalRequest(
    LocalDate expiresAt,
    String overrideNote
) {}
