package de.makibytes.registerwerk.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;

public record WalletExportRequest(
        @NotBlank String password
) {}
