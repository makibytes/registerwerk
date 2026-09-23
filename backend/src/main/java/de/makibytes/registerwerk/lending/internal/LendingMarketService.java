package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.DefiInteropModel;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.events.LendingMarketRegisteredEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator registration and jurisdiction-aware listing of {@link LendingMarket} rows. Markets
 * are entered here after being deployed via {@code EwpgRepoMarketFactory.createMarket} — this
 * service never deploys anything itself (see {@code contracts/script/DeployRepoMarkets.s.sol}
 * for the on-chain side).
 */
@Service
@Transactional
public class LendingMarketService implements de.makibytes.registerwerk.lending.api.LendingMarketRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LendingMarketService.class);
    private static final BigInteger BPS = BigInteger.valueOf(10_000);
    private static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    private final LendingMarketRepository marketRepository;
    private final AssetRepository assetRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final RepoMarketOnchainReader onchainReader;
    private final ApplicationEventPublisher eventPublisher;
    private final JurisdictionRequirementConfig jurisdictionConfig;
    private final LendingReleaseGate releaseGate;

    LendingMarketService(
            LendingMarketRepository marketRepository,
            AssetRepository assetRepository,
            ChainConfigRepository chainConfigRepository,
            RepoMarketOnchainReader onchainReader,
            ApplicationEventPublisher eventPublisher,
            JurisdictionRequirementConfig jurisdictionConfig,
            LendingReleaseGate releaseGate) {
        this.marketRepository = marketRepository;
        this.assetRepository = assetRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.onchainReader = onchainReader;
        this.eventPublisher = eventPublisher;
        this.jurisdictionConfig = jurisdictionConfig;
        this.releaseGate = releaseGate;
    }

    /**
     * A market plus the jurisdiction/naming context resolved from its linked collateral asset.
     * {@code micarApplicable}/{@code defiInteropModel} are the jurisdiction's compliance-profile
     * deltas from {@link JurisdictionRequirementConfig} (LU_CSSF/FR_AMF/LI_TVTG differ from
     * DE_EWPG here) — null when no jurisdiction is resolved at all.
     *
     * @param effectiveStatus {@link #market}'s persisted {@code status}, EXCEPT when it is
     *                        {@code ACTIVE} and the on-chain {@code EwpgRepoMarket.borrowPaused}
     *                        flag reads {@code true} — then {@code PAUSED}, live from chain
     *                        rather than a DB row nothing currently sets (an operator pauses
     *                        borrowing directly on-chain via {@code setBorrowPaused}, with no
     *                        corresponding backend write path). A chain-read failure is treated
     *                        as {@code PAUSED}; lending discovery must fail closed.
     * @param riskParametersLegacy the deployed market does not meet today's construction checks
     *                        (see {@link #riskParametersSound}; its oracle is not quoted in its loan
     *                        token; or it predates the instance binding). Such a market was
     *                        registered before those checks existed and stays listed so borrowers
     *                        can still repay, claim and withdraw, but new borrowing and supply
     *                        must not be offered. A chain-read failure counts as legacy (fail
     *                        closed). Always false for a RETIRED market, which is never read.
     * @param operatorOrg     the org operating the market on-chain ({@code operatorOrg()}); null
     *                        for a legacy market or when the read failed.
     * @param treasury        the market's fixed reserve recipient ({@code treasury()}); null as
     *                        for {@code operatorOrg}.
     */
    public record MarketView(
            LendingMarket market, Jurisdiction jurisdiction, String collateralAssetName, String collateralIsin,
            Boolean micarApplicable, DefiInteropModel defiInteropModel, LendingMarketStatus effectiveStatus,
            boolean riskParametersLegacy, String operatorOrg, String treasury) {}

    /** On-chain facts about a market's risk construction and operating binding — see {@link MarketView}. */
    private record OnchainBinding(boolean riskParametersLegacy, String operatorOrg, String treasury) {}

    /**
     * Mirrors {@code EwpgRepoMarket}'s construction check: {@code lltv × (1 + bonus)} must stay
     * strictly below 1 and, unless the oracle opts out with {@code type(uint256).max}, within
     * {@code 1 − maxDeviation}. Otherwise liquidating a position just below health factor 1
     * after one in-tolerance oracle move cannot pay the liquidation bonus and leaves bad debt.
     */
    static boolean riskParametersSound(Integer lltvBps, Integer liquidationBonusBps, BigInteger oracleMaxDeviationBps) {
        if (lltvBps == null || liquidationBonusBps == null || oracleMaxDeviationBps == null) {
            return false;
        }
        BigInteger incentiveAdjustedLltv = BigInteger.valueOf(lltvBps)
                .multiply(BPS.add(BigInteger.valueOf(liquidationBonusBps)));
        if (incentiveAdjustedLltv.compareTo(BPS.multiply(BPS)) >= 0) {
            return false;
        }
        if (oracleMaxDeviationBps.equals(UINT256_MAX)) {
            return true;
        }
        return oracleMaxDeviationBps.compareTo(BPS) < 0
                && incentiveAdjustedLltv.compareTo(BPS.multiply(BPS.subtract(oracleMaxDeviationBps))) <= 0;
    }

    @Override
    public void registerVerifiedMarket(UUID chainConfigId, String marketAddress, String vaultAddress,
                                       UUID collateralAssetId, String loanRailCode,
                                       UUID registeredBy, String registeredByRole) {
        registerMarket(chainConfigId, marketAddress, vaultAddress, collateralAssetId, loanRailCode,
                registeredBy, registeredByRole);
    }

    public MarketView registerMarket(
            UUID chainConfigId, String marketAddress, String vaultAddress, UUID collateralAssetId,
            String loanRailCode, UUID actorId, String actorRole) {

        releaseGate.requireReleased();

        if (marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress)) {
            throw new IllegalArgumentException(
                    "Market " + marketAddress + " is already registered on this chain");
        }
        ChainConfig chainConfig = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        if (!chainConfig.isEnabled() || chainConfig.getChainType() != ChainConfig.ChainType.EVM) {
            throw new IllegalArgumentException("Lending markets require an enabled EVM chain");
        }
        assetRepository.findById(collateralAssetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", collateralAssetId));

        RepoMarketOnchainReader.MarketParameters parameters =
                onchainReader.marketParameters(chainConfig.getIdentifier(), marketAddress);
        if (parameters.maxLtvBps() <= 0 || parameters.maxLtvBps() >= parameters.lltvBps()) {
            throw new IllegalArgumentException("Deployed market has invalid max-LTV/liquidation-LTV parameters");
        }
        if (parameters.oracleQuoteToken() == null
                || !parameters.oracleQuoteToken().equalsIgnoreCase(parameters.loanTokenAddress())) {
            throw new IllegalArgumentException(
                    "Deployed market's price oracle is not quoted in the market's loan token");
        }
        if (!riskParametersSound(parameters.lltvBps(), parameters.liquidationBonusBps(),
                parameters.oracleMaxDeviationBps())) {
            throw new IllegalArgumentException(
                    "Deployed market's liquidation LTV and bonus exceed the oracle's deviation haircut: "
                            + "lltv × (1 + bonus) must be at most 1 − maxDeviation");
        }

        LendingMarket market = new LendingMarket();
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setVaultAddress(vaultAddress);
        market.setCollateralAssetId(collateralAssetId);
        market.setCollateralTokenAddress(parameters.collateralTokenAddress());
        market.setLoanTokenAddress(parameters.loanTokenAddress());
        market.setLoanRailCode(loanRailCode);
        market.setLoanTokenDecimals(parameters.loanTokenDecimals());
        market.setMaxLtvBps(parameters.maxLtvBps());
        market.setLltvBps(parameters.lltvBps());
        market.setLiquidationBonusBps(parameters.liquidationBonusBps());
        market.setBaseRateWad(parameters.baseRateWad());
        market.setSlopeWad(parameters.slopeWad());
        market.setMaxPriceAgeSeconds(parameters.maxPriceAgeSeconds());
        market.setLiquidationGracePeriodSeconds(parameters.liquidationGracePeriodSeconds());
        market.setPriceOracleAddress(parameters.priceOracleAddress());
        market.setRegisteredBy(actorId);
        market = marketRepository.save(market);

        eventPublisher.publishEvent(new LendingMarketRegisteredEvent(market.getId(), actorId, actorRole,
                Map.of("marketAddress", marketAddress,
                        "maxLtvBps", parameters.maxLtvBps(),
                        "lltvBps", parameters.lltvBps(),
                        "operatorOrg", parameters.operatorOrg(),
                        "treasury", parameters.treasury())));

        return toView(market);
    }

    public List<MarketView> listMarkets(LendingMarketStatus statusFilter) {
        releaseGate.requireReleased();
        List<LendingMarket> markets = statusFilter != null
                ? marketRepository.findByStatus(statusFilter)
                : marketRepository.findAll();
        return markets.stream()
                .map(this::toView)
                .filter(view -> statusFilter == null || view.effectiveStatus() == statusFilter)
                .toList();
    }

    public MarketView getMarket(UUID marketId) {
        releaseGate.requireReleased();
        return toView(requireMarket(marketId));
    }

    public LendingMarket requireMarket(UUID marketId) {
        return marketRepository.findById(marketId)
                .orElseThrow(() -> new EntityNotFoundException("LendingMarket", marketId));
    }

    /**
     * Borrow-terms preview for a prospective {@code collateralAmount}, read live from chain (no
     * write, no wallet needed) — backs the frontend's guided borrow stepper so a trader sees
     * real numbers before signing anything.
     */
    public LendingQuote quote(UUID marketId, BigInteger collateralAmount) {
        releaseGate.requireReleased();
        if (collateralAmount == null || collateralAmount.signum() <= 0) {
            throw new IllegalArgumentException("Collateral amount must be greater than zero");
        }
        LendingMarket market = requireMarket(marketId);
        requireOperational(market);
        if (market.getMaxLtvBps() == null || market.getMaxPriceAgeSeconds() == null) {
            throw new IllegalStateException(
                    "Lending market metadata predates on-chain verification; re-register it before quoting");
        }
        String chainIdentifier = resolveChainIdentifier(market.getChainConfigId());

        RepoMarketOnchainReader.PriceMark mark = onchainReader.price(
                chainIdentifier, market.getPriceOracleAddress(), market.getCollateralTokenAddress());
        BigInteger utilizationWad = onchainReader.utilization(chainIdentifier, market.getMarketAddress());
        BigInteger borrowRateWad = onchainReader.borrowRate(chainIdentifier, market.getMarketAddress());

        BigInteger collateralValue = collateralAmount.multiply(mark.pricePerUnit());
        BigInteger collateralLimitedBorrow = collateralValue.multiply(BigInteger.valueOf(market.getMaxLtvBps()))
                .divide(BigInteger.valueOf(10_000));
        BigInteger availableLiquidity = onchainReader.availableLiquidity(
                chainIdentifier, market.getMarketAddress(), market.getLoanTokenAddress());
        BigInteger maxBorrow = collateralLimitedBorrow.min(availableLiquidity);
        BigInteger age = BigInteger.valueOf(Instant.now().getEpochSecond()).subtract(mark.updatedAt());
        boolean oracleReliable = mark.pricePerUnit().signum() > 0
                && mark.updatedAt().signum() > 0
                && age.signum() >= 0
                && (market.getMaxPriceAgeSeconds().signum() == 0
                    || age.compareTo(market.getMaxPriceAgeSeconds()) <= 0);

        return new LendingQuote(
                marketId, collateralAmount, mark.pricePerUnit(), mark.updatedAt(),
                maxBorrow, market.getMaxLtvBps(), market.getLltvBps(), utilizationWad, borrowRateWad,
                availableLiquidity, oracleReliable);
    }

    public record LendingQuote(
            UUID marketId, BigInteger collateralAmount, BigInteger pricePerUnit, BigInteger priceUpdatedAt,
            BigInteger maxBorrowAmount, int maxLtvBps, int lltvBps, BigInteger utilizationWad,
            BigInteger borrowRateWad, BigInteger availableLiquidity, boolean oracleReliable) {}

    String resolveChainIdentifier(UUID chainConfigId) {
        return resolveChainConfig(chainConfigId).getIdentifier();
    }

    /** Used by {@code LendingPositionService} to reach the chain's Graph Node URL/subgraph name
     *  for {@code RepoMarketEventReader} — {@link #resolveChainIdentifier}
     *  alone only exposes the identifier string. */
    ChainConfig resolveChainConfig(UUID chainConfigId) {
        return chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
    }

    void requireOperational(LendingMarket market) {
        if (resolveEffectiveStatus(market) != LendingMarketStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Lending market " + market.getId() + " is not operational");
        }
    }

    private MarketView toView(LendingMarket market) {
        LendingMarketStatus effectiveStatus = resolveEffectiveStatus(market);
        OnchainBinding binding = resolveOnchainBinding(market);
        if (market.getCollateralAssetId() == null) {
            return view(market, null, null, null, null, null, effectiveStatus, binding);
        }
        Optional<Asset> asset = assetRepository.findById(market.getCollateralAssetId());
        return asset
                .map(a -> {
                    Jurisdiction jurisdiction = a.getJurisdiction();
                    if (jurisdiction == null) {
                        return view(market, null, a.getName(), a.getIsin(), null, null, effectiveStatus, binding);
                    }
                    var compliance = jurisdictionConfig.getProfile(jurisdiction).compliance();
                    return view(market, jurisdiction, a.getName(), a.getIsin(),
                            compliance.micarApplicable(), compliance.defiInteropModel(), effectiveStatus, binding);
                })
                .orElseGet(() -> view(market, null, null, null, null, null, effectiveStatus, binding));
    }

    private static MarketView view(
            LendingMarket market, Jurisdiction jurisdiction, String collateralAssetName, String collateralIsin,
            Boolean micarApplicable, DefiInteropModel defiInteropModel, LendingMarketStatus effectiveStatus,
            OnchainBinding binding) {
        return new MarketView(market, jurisdiction, collateralAssetName, collateralIsin, micarApplicable,
                defiInteropModel, effectiveStatus, binding.riskParametersLegacy(), binding.operatorOrg(),
                binding.treasury());
    }

    /**
     * Live read of the facts behind {@link MarketView#riskParametersLegacy} plus the market's
     * operating org and treasury. The stored LLTV/bonus are immutable on-chain; the oracle's
     * deviation cap is read live because an oracle deployed before it became decrease-only could
     * have raised it after the market was created. Any read failure (including a market or
     * oracle predating these getters) counts as legacy.
     */
    private OnchainBinding resolveOnchainBinding(LendingMarket market) {
        if (market.getStatus() == LendingMarketStatus.RETIRED) {
            return new OnchainBinding(false, null, null);
        }
        String operatorOrg = null;
        String treasury = null;
        boolean legacy = true;
        try {
            String chainIdentifier = resolveChainIdentifier(market.getChainConfigId());
            String quoteToken = onchainReader.oracleQuoteToken(chainIdentifier, market.getPriceOracleAddress());
            BigInteger maxDeviationBps =
                    onchainReader.oracleMaxDeviationBps(chainIdentifier, market.getPriceOracleAddress());
            operatorOrg = onchainReader.operatorOrg(chainIdentifier, market.getMarketAddress());
            treasury = onchainReader.treasury(chainIdentifier, market.getMarketAddress());
            legacy = operatorOrg == null
                    || quoteToken == null
                    || !quoteToken.equalsIgnoreCase(market.getLoanTokenAddress())
                    || !riskParametersSound(market.getLltvBps(), market.getLiquidationBonusBps(), maxDeviationBps);
        } catch (RuntimeException e) {
            log.warn("Risk-parameter read failed for market {}; treating it as legacy: {}",
                    market.getMarketAddress(), e.getMessage());
        }
        return new OnchainBinding(legacy, operatorOrg, treasury);
    }

    /**
     * Reflects the on-chain {@code borrowPaused} flag as {@code PAUSED} for an otherwise-ACTIVE
     * market — see {@link MarketView#effectiveStatus}. Never overrides an already-PAUSED or
     * RETIRED persisted status. Any read failure (RPC hiccup, unresolvable chain identifier, …)
     * is logged and treated as PAUSED so discovery and quotes cannot advertise an unverified
     * ACTIVE market.
     */
    private LendingMarketStatus resolveEffectiveStatus(LendingMarket market) {
        if (market.getStatus() != LendingMarketStatus.ACTIVE) {
            return market.getStatus();
        }
        try {
            String chainIdentifier = resolveChainIdentifier(market.getChainConfigId());
            boolean paused = onchainReader.borrowPaused(chainIdentifier, market.getMarketAddress());
            return paused ? LendingMarketStatus.PAUSED : LendingMarketStatus.ACTIVE;
        } catch (RuntimeException e) {
            log.warn("borrowPaused read failed for market {}: {}", market.getMarketAddress(), e.getMessage());
            return LendingMarketStatus.PAUSED;
        }
    }
}
