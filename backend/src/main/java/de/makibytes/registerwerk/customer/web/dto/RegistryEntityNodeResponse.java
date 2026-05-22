package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.kyc.api.KycStatus;

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
