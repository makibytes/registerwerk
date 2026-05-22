package de.makibytes.registerwerk.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(max = 200) String password
) {}
