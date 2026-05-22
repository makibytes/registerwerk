package de.makibytes.registerwerk.customer.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RegistryRelationshipResponse(
    UUID assetId,
    String assetNumber,
    String assetName,
    String assetStatus,
    UUID issuerId,
    UUID investorId,
    BigDecimal nominalAmount,
    boolean whitelisted
) {}
