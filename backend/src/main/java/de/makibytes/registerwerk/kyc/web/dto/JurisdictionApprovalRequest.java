package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request body for approving jurisdiction-specific KYC.
 *
 * @param expiresAt    optional KYC approval expiry date; defaults to now + 1 year if omitted
 * @param overrideNote required when approving despite missing/expired compliance requirements
 */
public record JurisdictionApprovalRequest(
    @Future LocalDate expiresAt,
    @Size(max = 2000) String overrideNote
) {}
