package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.Size;

/** Optional body of {@code POST /admin/users/{id}/disable}; {@code reason} is mandatory in Entra mode. */
public record DisableUserRequest(@Size(max = 1000) String reason) {}
