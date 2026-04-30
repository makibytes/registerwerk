package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.AppUserRole;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record UpdateCompanyUserRolesRequest(@NotEmpty Set<AppUserRole> roles) {}
