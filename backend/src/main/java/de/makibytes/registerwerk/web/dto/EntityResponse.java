package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.domain.enums.KycStatus;

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
    Instant createdAt
) {}
