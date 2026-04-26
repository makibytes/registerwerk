package de.makibytes.registerwerk.web.dto.admin;

import java.math.BigInteger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for burning tokens (cancellation by issuer).
 */
public record BurnRequest(
    @NotBlank(message = "fromAddress is required")
    String fromAddress,

    @NotNull(message = "amount is required")
    BigInteger amount
) {}
