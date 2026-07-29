package de.makibytes.registerwerk.trading.web.dto;

import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.TokenStandard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Enriched response DTO for an investor's holding, including resolved asset metadata.
 * {@code chain} is what lets the frontend show capability-honest UI (e.g. only offering
 * repo/lending's "Pledge & borrow" for EVM holdings) instead of a misleading affordance for
 * chains that fundamentally can't support it yet — see `core/lending/chain-capabilities.ts`.
 */
public record InvestmentResponse(
    UUID id,
    UUID assetId,
    String assetNumber,
    String assetName,
    String isin,
    TokenStandard tokenStandard,
    AssetStatus assetStatus,
    UUID investorId,
    String walletAddress,
    Boolean whitelisted,
    String whitelistTxHash,
    BigDecimal nominalAmount,
    LocalDate acquisitionDate,
    Instant createdAt,
    Instant updatedAt,
    String externalId,
    Chain chain
) {}
