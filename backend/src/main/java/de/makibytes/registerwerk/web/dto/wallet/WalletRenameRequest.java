package de.makibytes.registerwerk.web.dto.wallet;

import jakarta.validation.constraints.NotBlank;

public record WalletRenameRequest(@NotBlank String name) {}
