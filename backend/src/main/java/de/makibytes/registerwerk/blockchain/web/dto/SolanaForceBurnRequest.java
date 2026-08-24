package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../solana-admin/force-burn}.
 *
 * <p>Legal basis: eWpG §26 Einziehung, exercised via the Token-2022 Permanent Delegate
 * extension ({@link de.makibytes.registerwerk.blockchain.internal.SolanaTokenAdminService}).
 *
 * @param tokenAccount base58 token account whose tokens will be cancelled
 * @param amount       amount in smallest token unit
 * @param decimals     token decimals (typically 6)
 * @param legalBasis   mandatory reference to the legal authority
 */
public record SolanaForceBurnRequest(
        @NotBlank
        @Pattern(regexp = "^[1-9A-HJ-NP-Za-km-z]{32,44}$", message = "Must be a valid base58 Solana account")
        String tokenAccount,

        @NotNull
        @Positive
        BigInteger amount,

        @NotNull
        @PositiveOrZero
        Integer decimals,

        @NotBlank(message = "legalBasis is required for compulsory cancellation")
        String legalBasis
) {}
