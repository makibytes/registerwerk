package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TerminateEntityRequest(@NotBlank String reason) {}
