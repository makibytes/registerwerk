package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ImpersonateRequest(@NotNull UUID entityId) {}
