package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.enums.Network;
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
    Chain chain,
    Network network,
    OnchainLevel onchainLevel,
    AssetStatus status,
    Jurisdiction jurisdiction,
    Instant createdAt,
    Instant updatedAt,
    boolean hasTermSheet,
    String externalId
) {}
