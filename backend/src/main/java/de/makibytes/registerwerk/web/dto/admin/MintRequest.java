package de.makibytes.registerwerk.web.dto.admin;

import java.math.BigInteger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for minting tokens (issuer issuance).
 */
public record MintRequest(
    @NotBlank(message = "toAddress is required")
    String toAddress,

    @NotNull(message = "amount is required")
    BigInteger amount,

    String reason
) {}
