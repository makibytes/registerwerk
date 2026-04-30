package de.makibytes.registerwerk.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OnboardingCompleteRequest(
    @NotBlank String token,
    @NotBlank @Email String adminEmail,
    @NotBlank String adminName,
    String password
) {}
