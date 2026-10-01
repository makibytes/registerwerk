package de.makibytes.registerwerk.lending.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigInteger;

/**
 * Attributes a collateral outflow to one borrower: the borrower's pledged collateral is written
 * down to {@code attributableCollateral} (token base units). {@code forcedTransferRef} is the
 * 32-byte hash of the forced-transfer transaction; {@code legalBasis} the instruction reference.
 */
public record ReconcileCollateralRequest(
        @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String borrowerWallet,
        @NotNull @PositiveOrZero BigInteger attributableCollateral,
        @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{64}$", message = "Must be a 32-byte hex value")
        String forcedTransferRef,
        @NotBlank String legalBasis
) {}
