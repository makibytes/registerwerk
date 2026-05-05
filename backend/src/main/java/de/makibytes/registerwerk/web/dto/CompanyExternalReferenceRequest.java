package de.makibytes.registerwerk.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CompanyExternalReferenceRequest(@NotBlank String externalId) {}
