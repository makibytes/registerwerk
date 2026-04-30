package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.AppUserRole;
import de.makibytes.registerwerk.domain.enums.UserAuthProvider;

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
