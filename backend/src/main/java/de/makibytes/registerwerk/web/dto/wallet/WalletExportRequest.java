package de.makibytes.registerwerk.web.dto.wallet;

import jakarta.validation.constraints.NotBlank;

public record WalletExportRequest(
        @NotBlank String password
) {}
