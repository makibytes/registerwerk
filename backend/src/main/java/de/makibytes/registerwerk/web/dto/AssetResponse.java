package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.enums.OnchainLevel;
import de.makibytes.registerwerk.domain.enums.TokenStandard;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for asset data.
 */
public record AssetResponse(
    UUID id,
    String assetNumber,
    UUID issuerId,
    String name,
    String isin,
    TokenStandard tokenStandard,
    OnchainLevel onchainLevel,
    AssetStatus status,
    Jurisdiction jurisdiction,
    Instant createdAt,
    boolean hasTermSheet
) {}
