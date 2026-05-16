package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CantonFreezeHoldingRequest(
        @NotBlank String holdingContractId
) {}
