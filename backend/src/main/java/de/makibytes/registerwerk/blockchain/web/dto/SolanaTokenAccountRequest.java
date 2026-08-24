package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST .../solana-admin/freeze} and {@code .../thaw}.
 *
 * @param tokenAccount base58 token account to freeze/thaw
 */
public record SolanaTokenAccountRequest(
        @NotBlank
        @Pattern(regexp = "^[1-9A-HJ-NP-Za-km-z]{32,44}$", message = "Must be a valid base58 Solana account")
        String tokenAccount
) {}
