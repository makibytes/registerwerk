package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;

public record StepUpRequest(
        @NotBlank String code,
        String method   // "TOTP" (default) or "WEBAUTHN"
) {}
