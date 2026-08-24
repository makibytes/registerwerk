package de.makibytes.registerwerk.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for {@code POST /api/v1/public/auth/impersonate} — see AuthController's Javadoc. */
public record ImpersonateExchangeRequest(@NotBlank String token) {}
