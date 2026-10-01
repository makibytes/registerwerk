package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RebindIdentityRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {}
