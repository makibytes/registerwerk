package de.makibytes.registerwerk.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicPasswordResetCompleteRequest(
    @NotBlank String token,
    @NotBlank @Size(min = 8, max = 200) String password
) {}
