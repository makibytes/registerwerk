package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.internal.InstructingParty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload for adding a new asset holder (operator-executed against a recorded instruction, T3-13).
 */
public record HolderCreateRequest(
    @NotNull UUID investorId,
    @NotBlank String walletAddress,
    BigDecimal nominalAmount,
    LocalDate acquisitionDate,
    @NotNull InstructingParty instructingParty,
    @NotBlank String instructionReference
) {}
