package de.makibytes.registerwerk.lending.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Registers an already-deployed market. Immutable economic and token parameters are purposely
 * absent: the backend reads them from the contract so a typo cannot create a deceptive quote.
 */
public record RegisterLendingMarketRequest(
        @NotNull UUID chainConfigId,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String marketAddress,

        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String vaultAddress,

        @NotNull UUID collateralAssetId,

        String loanRailCode
) {}
