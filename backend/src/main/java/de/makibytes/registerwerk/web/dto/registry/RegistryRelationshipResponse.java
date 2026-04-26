package de.makibytes.registerwerk.web.dto.registry;

import de.makibytes.registerwerk.domain.enums.AssetStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record RegistryRelationshipResponse(
    UUID assetId,
    String assetNumber,
    String assetName,
    AssetStatus assetStatus,
    UUID issuerId,
    UUID investorId,
    BigDecimal nominalAmount,
    boolean whitelisted
) {}
