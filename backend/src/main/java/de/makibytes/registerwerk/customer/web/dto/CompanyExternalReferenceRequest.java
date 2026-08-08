package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyExternalReferenceRequest(@NotBlank @Size(max = 255) String externalId) {}
