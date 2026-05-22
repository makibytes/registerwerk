package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record InviteCompanyUserRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotEmpty Set<AppUserRole> roles
) {}
