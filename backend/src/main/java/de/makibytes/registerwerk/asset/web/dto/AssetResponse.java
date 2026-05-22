package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.asset.api.TokenStandard;

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
