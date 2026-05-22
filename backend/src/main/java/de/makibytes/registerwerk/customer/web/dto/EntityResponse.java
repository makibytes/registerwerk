package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for legal entity data.
 */
public record EntityResponse(
    UUID id,
    String entityNumber,
    EntityType type,
    EntityStatus status,
    String currentName,
    String leiCode,
    String registrationNumber,
    KycStatus kycStatus,
    Instant createdAt,
    String externalId
) {}
