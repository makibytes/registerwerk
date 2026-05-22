package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigInteger;

/**
 * Request body for {@code POST /api/v1/deployments/{depId}/slots/{slotId}/mint}.
 *
 * @param toAddress   Whitelisted recipient EVM address.
 * @param value       Number of units to mint in smallest denomination.
 */
public record MintIntoSlotRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String toAddress,

        @NotNull @Positive
        BigInteger value
) {}
