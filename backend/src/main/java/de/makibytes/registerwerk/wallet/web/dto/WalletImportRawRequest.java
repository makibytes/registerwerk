package de.makibytes.registerwerk.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record WalletImportRawRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "EVM|SOLANA") String type,
        @NotBlank String privateKey
) {}
