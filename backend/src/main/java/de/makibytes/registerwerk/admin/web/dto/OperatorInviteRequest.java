package de.makibytes.registerwerk.admin.web.dto;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;
import java.util.UUID;

public record OperatorInviteRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    UUID legalEntityId,
    @NotEmpty Set<AppUserRole> roles
) {}
