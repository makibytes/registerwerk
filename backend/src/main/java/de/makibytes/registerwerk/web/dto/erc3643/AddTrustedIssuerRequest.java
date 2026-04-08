package de.makibytes.registerwerk.web.dto.erc3643;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body for registering a trusted claim issuer in a T-REX suite.
 */
public record AddTrustedIssuerRequest(
    /** On-chain address (EOA or contract) of the issuer. */
    @NotBlank String issuerAddress,
    /** Claim topic numbers this issuer is authorised to sign (e.g. [1, 2] = KYC + AML). */
    @NotNull @NotEmpty List<Long> claimTopics,
    /**
     * Optional: UUID of a LegalEntity in this registry that acts as the issuer.
     * Set this when the registry operator itself issues claims via {@code ClaimIssuanceService}.
     * Leave null for external KYC providers.
     */
    UUID legalEntityId
) {}
