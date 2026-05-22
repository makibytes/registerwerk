package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload for adding a new asset holder.
 */
public record HolderCreateRequest(
    @NotNull UUID investorId,
    @NotBlank String walletAddress,
    BigDecimal nominalAmount,
    LocalDate acquisitionDate
) {}
