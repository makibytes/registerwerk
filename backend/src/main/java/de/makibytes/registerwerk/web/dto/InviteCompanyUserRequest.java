package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.AppUserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record InviteCompanyUserRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotEmpty Set<AppUserRole> roles
) {}
