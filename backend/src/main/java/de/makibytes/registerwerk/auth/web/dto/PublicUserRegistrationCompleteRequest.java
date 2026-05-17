package de.makibytes.registerwerk.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicUserRegistrationCompleteRequest(
    @NotBlank String token,
    @NotBlank String name,
    @NotBlank @Size(min = 8, max = 200) String password
) {}
