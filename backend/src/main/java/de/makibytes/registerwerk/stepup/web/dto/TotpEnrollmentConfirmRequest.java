package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TotpEnrollmentConfirmRequest(@NotBlank String code) {}
