package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CompanyExternalReferenceRequest(@NotBlank String externalId) {}
