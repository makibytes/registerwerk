package de.makibytes.registerwerk.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicUserRegistrationCompleteRequest(
    @NotBlank String token,
    @NotBlank String name,
    @NotBlank @Size(min = 8, max = 200) String password
) {}
