package de.makibytes.registerwerk.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /api/v1/public/auth/impersonate}: the one-time handoff code from the URL fragment. */
public record ImpersonateExchangeRequest(@NotBlank @Size(max = 200) String code) {}
