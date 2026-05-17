package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record CompanyUserResponse(
    UUID id,
    String email,
    String name,
    Set<AppUserRole> roles,
    UUID entityId,
    boolean enabled,
    Instant lastLoginAt,
    UserAuthProvider authProvider,
    boolean passwordSetupRequired
) {}
