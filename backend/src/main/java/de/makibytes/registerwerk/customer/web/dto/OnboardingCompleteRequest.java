package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OnboardingCompleteRequest(
    @NotBlank String token,
    @NotBlank @Email String adminEmail,
    @NotBlank String adminName,
    @Size(min = 8, max = 200) String password
) {}
