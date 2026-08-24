package de.makibytes.registerwerk.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Registers an existing, non-exportable secp256k1 key held by the configured HSM. */
public record WalletAttachHsmRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String keyAlias,
        @NotBlank @Pattern(regexp = "0x[0-9a-fA-F]{40}") String address
) {}
