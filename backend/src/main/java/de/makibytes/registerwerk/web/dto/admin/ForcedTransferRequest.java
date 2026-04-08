package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../admin/forced-transfer}.
 *
 * <p>Legal basis: eWpG §24 Berichtigung (BaFin/court-ordered register correction).
 *
 * @param from        Source wallet address (must hold the tokens)
 * @param to          Destination wallet address
 * @param value       Amount in smallest token unit (ERC-20/ERC-1155) or tokenId (ERC-721)
 * @param legalBasis  Mandatory reference to the legal authority (e.g. "BaFin Bescheid Az. 2025-001")
 */
public record ForcedTransferRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String from,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String to,

        @NotNull
        @Positive
        BigInteger value,

        @NotBlank(message = "legalBasis is required for forced transfers")
        String legalBasis
) {}
