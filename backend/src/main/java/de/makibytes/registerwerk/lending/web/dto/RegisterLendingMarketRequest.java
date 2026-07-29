package de.makibytes.registerwerk.lending.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigInteger;
import java.util.UUID;

/** Operator request body for {@code POST /api/v1/lending/markets} — registers an already-deployed {@code EwpgRepoMarket}. */
public record RegisterLendingMarketRequest(
        @NotNull UUID chainConfigId,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String marketAddress,

        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String vaultAddress,

        UUID collateralAssetId,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String collateralTokenAddress,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String loanTokenAddress,

        String loanRailCode,

        @NotNull @Min(1) @Max(10_000) Integer lltvBps,

        @NotNull @Min(0) @Max(10_000) Integer liquidationBonusBps,

        @NotNull BigInteger baseRateWad,

        @NotNull BigInteger slopeWad,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String priceOracleAddress
) {}
