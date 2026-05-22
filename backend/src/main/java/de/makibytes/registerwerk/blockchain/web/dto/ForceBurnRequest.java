package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../admin/force-burn}.
 *
 * <p>Legal basis: eWpG §26 Einziehung (BaFin compulsory cancellation of electronic securities).
 *
 * @param from        Wallet address whose tokens will be cancelled
 * @param value       Amount in smallest token unit (ERC-20/ERC-1155) or tokenId (ERC-721)
 * @param legalBasis  Mandatory reference to the legal authority (e.g. "BaFin Einziehungsverfügung Az. 2025-002")
 */
public record ForceBurnRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$", message = "Must be a valid EVM address")
        String from,

        @NotNull
        @Positive
        BigInteger value,

        @NotBlank(message = "legalBasis is required for compulsory cancellation")
        String legalBasis
) {}
