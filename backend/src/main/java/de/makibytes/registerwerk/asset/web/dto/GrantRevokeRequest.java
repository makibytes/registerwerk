package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.NotBlank;

public record GrantRevokeRequest(@NotBlank String reason) {}
