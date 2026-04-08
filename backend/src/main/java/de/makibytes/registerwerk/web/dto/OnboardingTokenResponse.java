package de.makibytes.registerwerk.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO containing the cleartext onboarding token (returned once only).
 */
public record OnboardingTokenResponse(
    String token,
    Instant expiresAt,
    UUID entityId
) {}
