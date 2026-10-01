package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code reason} is mandatory (min 15 chars) and lands in the audit trail; {@code ticket} is optional. */
public record ImpersonateRequest(
        @NotNull UUID entityId,
        @NotBlank @Size(min = 15, max = 500) String reason,
        @Size(max = 100) String ticket) {}
