package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response DTO for a gas-sponsorship policy. */
public record GasSponsorshipPolicyResponse(
    UUID id,
    UUID assetDeploymentId,
    UUID issuerId,
    GasSponsorshipPolicy.Sponsor sponsor,
    BigDecimal monthlyCapEth,
    Boolean active,
    Instant createdAt
) {}
