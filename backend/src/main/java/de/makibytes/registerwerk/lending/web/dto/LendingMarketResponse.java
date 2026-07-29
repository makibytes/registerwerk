package de.makibytes.registerwerk.lending.web.dto;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.DefiInteropModel;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code micarApplicable}/{@code defiInteropModel} are the collateral asset's jurisdiction
 * compliance-profile deltas (see {@code kyc.api.JurisdictionRequirementConfig}) — null when the
 * market has no linked collateral asset or that asset has no jurisdiction set.
 */
public record LendingMarketResponse(
        UUID id,
        UUID chainConfigId,
        String marketAddress,
        String vaultAddress,
        UUID collateralAssetId,
        String collateralAssetName,
        String collateralIsin,
        String collateralTokenAddress,
        String loanTokenAddress,
        String loanRailCode,
        Integer lltvBps,
        Integer liquidationBonusBps,
        BigInteger baseRateWad,
        BigInteger slopeWad,
        String priceOracleAddress,
        LendingMarketStatus status,
        Jurisdiction jurisdiction,
        Boolean micarApplicable,
        DefiInteropModel defiInteropModel,
        Instant createdAt
) {}
