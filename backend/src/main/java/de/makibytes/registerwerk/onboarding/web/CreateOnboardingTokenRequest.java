package de.makibytes.registerwerk.onboarding.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateOnboardingTokenRequest(@NotNull UUID entityId) {}
