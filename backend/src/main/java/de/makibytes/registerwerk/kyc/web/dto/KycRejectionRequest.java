package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KycRejectionRequest(@NotBlank @Size(max = 2000) String reason) {}
