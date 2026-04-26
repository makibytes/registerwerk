package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../admin/force-burn-single} (ERC-1155 only).
 * Burns {@code amount} of token {@code id} from {@code from}.
 */
public record ForceBurnSingleRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String from,

        @NotNull
        @PositiveOrZero
        BigInteger id,

        @NotNull
        @Positive
        BigInteger amount,

        @NotBlank(message = "legalBasis is required for forced burns")
        String legalBasis
) {}
