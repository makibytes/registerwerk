package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.NotBlank;

public record HolderBlockLiftRequest(@NotBlank String reason) {}
