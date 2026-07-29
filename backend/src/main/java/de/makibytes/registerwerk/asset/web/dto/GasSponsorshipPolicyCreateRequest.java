package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request payload for creating a gas-sponsorship policy, either scoped to one deployment or
 * as an issuer-level default (the controller sets whichever scope applies).
 */
public record GasSponsorshipPolicyCreateRequest(
    @NotNull GasSponsorshipPolicy.Sponsor sponsor,
    BigDecimal monthlyCapEth
) {}
