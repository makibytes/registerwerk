package de.makibytes.registerwerk.webhook.web.dto;

import jakarta.validation.constraints.NotNull;

public record SetEnabledRequest(@NotNull Boolean enabled) {}
