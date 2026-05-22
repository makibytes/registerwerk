package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.asset.api.AssetStatus;

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
