package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../admin/forced-transfer-single} (ERC-1155 only).
 * Transfers {@code amount} of token {@code id} from {@code from} to {@code to}.
 */
public record ForcedTransferSingleRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String from,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String to,

        @NotNull
        @PositiveOrZero
        BigInteger id,

        @NotNull
        @Positive
        BigInteger amount,

        @NotBlank(message = "legalBasis is required for forced transfers")
        String legalBasis
) {}
