package de.makibytes.registerwerk.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registers an existing, non-exportable secp256k1 key held by the configured cloud KMS (T7-05). The
 * address is derived from the KMS public key; {@code address} is optional and, when given, must match.
 */
public record WalletAttachKmsRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String keyVersion,
        @Pattern(regexp = "|0x[0-9a-fA-F]{40}") String address
) {}
