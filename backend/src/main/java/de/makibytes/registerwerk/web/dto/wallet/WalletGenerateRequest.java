package de.makibytes.registerwerk.web.dto.wallet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record WalletGenerateRequest(
        @NotBlank String name,
        @NotNull @Pattern(regexp = "EVM|SOLANA") String type
) {}
