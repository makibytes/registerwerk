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
 * {@code riskParametersLegacy}/{@code operatorOrg}/{@code treasury}: see
 * {@code LendingMarketService.MarketView} — a legacy market must not be offered for new
 * borrowing or supply; repay, claim and withdraw stay available.
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
        Integer loanTokenDecimals,
        Integer maxLtvBps,
        Integer lltvBps,
        Integer liquidationBonusBps,
        BigInteger baseRateWad,
        BigInteger slopeWad,
        BigInteger maxPriceAgeSeconds,
        BigInteger liquidationGracePeriodSeconds,
        String priceOracleAddress,
        LendingMarketStatus status,
        Jurisdiction jurisdiction,
        Boolean micarApplicable,
        DefiInteropModel defiInteropModel,
        Instant createdAt,
        boolean riskParametersLegacy,
        String operatorOrg,
        String treasury,
        // COLLATERAL_SHORTFALL | BINDING_UNVERIFIED | BORROW_PAUSED_ONCHAIN | CHAIN_READ_FAILED when
        // status is PAUSED although the market is registered ACTIVE; null otherwise. Hide borrow when set.
        String pauseReason,
        // False when re-verification found the factory/collateral/loan-token binding broken (operator view only;
        // such markets are not listed for customers).
        boolean bindingVerified,
        String bindingFailure,
        boolean collateralShortfall
) {}
