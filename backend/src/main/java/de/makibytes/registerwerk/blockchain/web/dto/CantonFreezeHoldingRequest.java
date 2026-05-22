package de.makibytes.registerwerk.blockchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CantonFreezeHoldingRequest(
        @NotBlank String holdingContractId
) {}
