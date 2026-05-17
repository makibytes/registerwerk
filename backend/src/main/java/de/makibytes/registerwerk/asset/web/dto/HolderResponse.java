package de.makibytes.registerwerk.asset.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for an asset holder record.
 */
public record HolderResponse(
    UUID id,
    UUID assetId,
    UUID investorId,
    String walletAddress,
    Boolean whitelisted,
    BigDecimal nominalAmount,
    LocalDate acquisitionDate,
    String externalId
) {}
