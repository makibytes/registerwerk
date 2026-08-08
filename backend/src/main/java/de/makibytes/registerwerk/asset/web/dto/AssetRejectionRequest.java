package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssetRejectionRequest(@NotBlank @Size(max = 2000) String reason) {}
