package de.makibytes.registerwerk.web.dto.registry;

import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.domain.enums.KycStatus;

import java.util.List;
import java.util.UUID;

public record RegistryEntityNodeResponse(
    UUID id,
    String entityNumber,
    String currentName,
    EntityType storedType,
    List<EntityType> roles,
    EntityStatus status,
    KycStatus kycStatus,
    long issuedAssetCount,
    long investmentCount,
    long linkedInvestorCount,
    long linkedIssuerCount
) {}
