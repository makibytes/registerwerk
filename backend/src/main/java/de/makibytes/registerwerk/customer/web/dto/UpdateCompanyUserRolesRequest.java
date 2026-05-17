package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record UpdateCompanyUserRolesRequest(@NotEmpty Set<AppUserRole> roles) {}
