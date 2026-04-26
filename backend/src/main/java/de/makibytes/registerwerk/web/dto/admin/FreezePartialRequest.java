package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for {@code POST .../freeze-partial} and {@code .../unfreeze-partial} (ERC-3643 only).
 * Freezes or unfreezes a specific token amount for the given address.
 */
public record FreezePartialRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String address,

        @NotNull
        @Positive
        BigDecimal amount
) {}
