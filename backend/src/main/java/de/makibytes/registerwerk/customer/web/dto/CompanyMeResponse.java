package de.makibytes.registerwerk.customer.web.dto;

import java.time.Instant;
import java.util.UUID;

public record CompanyMeResponse(
    UUID id,
    String legalName,
    String registrationNumber,
    String jurisdiction,
    String entityType,
    String kycStatus,
    Instant kycVerifiedAt,
    boolean onboardingTokenUsed,
    String idpIssuerUrl,
    String idpClientId,
    Instant createdAt,
    Instant updatedAt,
    String externalId
) {}
