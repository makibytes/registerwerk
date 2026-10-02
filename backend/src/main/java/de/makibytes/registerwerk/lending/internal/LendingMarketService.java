package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.springframework.security.access.AccessDeniedException;
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
    private final LendingProperties properties;
    private final ContractAddressConfig contractAddresses;
    private final AssetDeploymentRepository deploymentRepository;
    private final PaymentRailRepository railRepository;
    private final PaymentRailChainAddressRepository railAddressRepository;
    private final LegalEntityRepository legalEntityRepository;

    LendingMarketService(
            LendingMarketRepository marketRepository,
            AssetRepository assetRepository,
            ChainConfigRepository chainConfigRepository,
            RepoMarketOnchainReader onchainReader,
            ApplicationEventPublisher eventPublisher,
            JurisdictionRequirementConfig jurisdictionConfig,
            LendingReleaseGate releaseGate,
            LendingProperties properties,
            ContractAddressConfig contractAddresses,
            AssetDeploymentRepository deploymentRepository,
            PaymentRailRepository railRepository,
            PaymentRailChainAddressRepository railAddressRepository,
            LegalEntityRepository legalEntityRepository) {
        this.properties = properties;
        this.contractAddresses = contractAddresses;
        this.deploymentRepository = deploymentRepository;
        this.railRepository = railRepository;
        this.railAddressRepository = railAddressRepository;
        this.legalEntityRepository = legalEntityRepository;
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
     * @param pauseReason     why {@code effectiveStatus} is PAUSED although the row is ACTIVE:
     *                        {@code COLLATERAL_SHORTFALL} (5B-10), {@code BINDING_UNVERIFIED} (5B-09),
     *                        {@code BORROW_PAUSED_ONCHAIN} or {@code CHAIN_READ_FAILED}; null otherwise.
     * @param treasury        the market's fixed reserve recipient ({@code treasury()}); null as
     *                        for {@code operatorOrg}.
     */
    public record MarketView(
            LendingMarket market, Jurisdiction jurisdiction, String collateralAssetName, String collateralIsin,
            Boolean micarApplicable, DefiInteropModel defiInteropModel, LendingMarketStatus effectiveStatus,
            boolean riskParametersLegacy, String operatorOrg, String treasury, String pauseReason,
            Long chainId, String chainName) {}

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
        Asset collateralAsset = assetRepository.findById(collateralAssetId)
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

        // 5B-09: the operator-supplied links are only trusted once they match the chain.
        String bindingFailure = bindingFailure(chainConfig, marketAddress, vaultAddress, collateralAsset,
                loanRailCode, parameters.collateralTokenAddress(), parameters.loanTokenAddress(),
                parameters.loanTokenDecimals());
        if (bindingFailure != null) {
            throw new IllegalArgumentException(bindingFailure);
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
        market.setCodeHash(onchainReader.codeHash(chainConfig.getIdentifier(), marketAddress));
        market.setSurplusSupported(onchainReader.surplusSupported(chainConfig.getIdentifier(), marketAddress));
        market.setBindingVerified(true);
        market.setBindingVerifiedAt(Instant.now());
        market = marketRepository.save(market);

        eventPublisher.publishEvent(new LendingMarketRegisteredEvent(market.getId(), actorId, actorRole,
                Map.of("marketAddress", marketAddress,
                        "maxLtvBps", parameters.maxLtvBps(),
                        "lltvBps", parameters.lltvBps(),
                        "operatorOrg", parameters.operatorOrg(),
                        "treasury", parameters.treasury())));

        return toView(market);
    }

    /**
     * 5B-09: null when the market's on-chain binding matches what the registry believes, otherwise
     * the reason. Reads fail closed: a transport error propagates as {@link IllegalStateException}.
     */
    String bindingFailure(ChainConfig chain, String marketAddress, String vaultAddress, Asset asset,
                          String loanRailCode, String collateralToken, String loanToken, int loanTokenDecimals) {
        String id = chain.getIdentifier();
        if (properties.isRequireFactory()) {
            String factory = contractAddresses.findRepoMarketFactory(id).orElse(null);
            if (factory == null) {
                return "No repo-market factory is configured for chain " + id
                        + " (registerwerk.contracts.repo-market-factory)";
            }
            if (!onchainReader.isFactoryMarket(id, factory, marketAddress)) {
                return "Market " + marketAddress + " was not deployed by the configured repo-market factory";
            }
        }
        if (asset.getStatus() != AssetStatus.ISSUED) {
            return "Collateral asset must be ISSUED (is " + asset.getStatus() + ")";
        }
        boolean collateralMatches = deploymentRepository.findByAssetId(asset.getId()).stream()
                .filter(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED)
                .filter(d -> chain.getId().equals(d.getChainConfigId()))
                .anyMatch(d -> d.getContractAddress() != null
                        && d.getContractAddress().equalsIgnoreCase(collateralToken));
        if (!collateralMatches) {
            return "Market collateral token " + collateralToken
                    + " is not the confirmed deployment of the collateral asset on this chain";
        }
        if (loanRailCode == null || loanRailCode.isBlank()) {
            return "A loan payment rail code is required";
        }
        PaymentRail rail = railRepository.findByCode(loanRailCode).orElse(null);
        if (rail == null || !rail.isEnabled() || rail.getRailType() != PaymentRailType.STABLECOIN) {
            return "Loan rail " + loanRailCode + " is not an enabled stablecoin rail";
        }
        boolean railMatches = railAddressRepository.findByPaymentRailId(rail.getId()).stream()
                .filter(a -> chain.getId().equals(a.getChainConfigId()))
                .anyMatch(a -> a.getTokenAddress() != null && a.getTokenAddress().equalsIgnoreCase(loanToken));
        if (!railMatches) {
            return "Market loan token " + loanToken + " is not the token of rail " + loanRailCode + " on this chain";
        }
        if (rail.getDecimals() == null || rail.getDecimals() != loanTokenDecimals) {
            return "Loan token decimals (" + loanTokenDecimals + ") differ from rail " + loanRailCode
                    + " decimals (" + rail.getDecimals() + ")";
        }
        if (vaultAddress != null && !vaultAddress.isBlank() && !onchainReader.hasCode(id, vaultAddress)) {
            return "No contract is deployed at vault address " + vaultAddress;
        }
        return null;
    }

    public record ReverifyResult(UUID marketId, String marketAddress, boolean verified, String failure) {}

    /**
     * 5B-09: re-checks every non-retired market against the chain. Mismatches are flagged
     * ({@code binding_verified=false}) and hide the market from customers, never deleted; a market
     * that verifies again is restored. An unreadable chain leaves the row unchanged.
     */
    public List<ReverifyResult> reverifyMarkets() {
        List<ReverifyResult> results = new java.util.ArrayList<>();
        for (LendingMarket market : marketRepository.findAll()) {
            if (market.getStatus() == LendingMarketStatus.RETIRED) continue;
            try {
                ChainConfig chain = resolveChainConfig(market.getChainConfigId());
                Asset asset = market.getCollateralAssetId() == null ? null
                        : assetRepository.findById(market.getCollateralAssetId()).orElse(null);
                String failure;
                if (asset == null) {
                    failure = "Collateral asset link is missing";
                } else {
                    failure = bindingFailure(chain, market.getMarketAddress(), market.getVaultAddress(), asset,
                            market.getLoanRailCode(), market.getCollateralTokenAddress(),
                            market.getLoanTokenAddress(),
                            market.getLoanTokenDecimals() == null ? -1 : market.getLoanTokenDecimals());
                }
                String hash = onchainReader.codeHash(chain.getIdentifier(), market.getMarketAddress());
                if (failure == null && market.getCodeHash() != null && !market.getCodeHash().equalsIgnoreCase(hash)) {
                    failure = "Market runtime code changed since registration";
                }
                if (failure == null && market.getCodeHash() == null) {
                    market.setCodeHash(hash);
                    market.setSurplusSupported(
                            onchainReader.surplusSupported(chain.getIdentifier(), market.getMarketAddress()));
                }
                market.setBindingVerified(failure == null);
                market.setBindingVerifiedAt(Instant.now());
                market.setBindingFailure(failure == null ? null
                        : failure.substring(0, Math.min(failure.length(), 500)));
                marketRepository.save(market);
                results.add(new ReverifyResult(market.getId(), market.getMarketAddress(), failure == null, failure));
            } catch (RuntimeException e) {
                log.warn("Re-verification of market {} skipped: {}", market.getMarketAddress(), e.getMessage());
                results.add(new ReverifyResult(market.getId(), market.getMarketAddress(), market.isBindingVerified(),
                        "not checked: " + e.getMessage()));
            }
        }
        return results;
    }

    /** T5-13 interim: only PROFESSIONAL / ELIGIBLE_COUNTERPARTY entities may borrow against securities. */
    public void requireLendingEligible(UUID legalEntityId) {
        if (legalEntityId == null) return; // operator token, no customer entity
        ClientCategory category = legalEntityRepository.findById(legalEntityId)
                .map(e -> e.getClientCategory()).orElse(null);
        if (category == null || category == ClientCategory.RETAIL) {
            throw new AccessDeniedException(
                    "Borrowing against securities is only available to classified professional clients and "
                            + "eligible counterparties; retail or unclassified clients are not admitted");
        }
    }

    public List<MarketView> listMarkets(LendingMarketStatus statusFilter) {
        return listMarkets(statusFilter, true);
    }

    public List<MarketView> listMarkets(LendingMarketStatus statusFilter, boolean includeUnverified) {
        releaseGate.requireReleased();
        List<LendingMarket> markets = statusFilter != null
                ? marketRepository.findByStatus(statusFilter)
                : marketRepository.findAll();
        return markets.stream()
                .filter(m -> includeUnverified || m.isBindingVerified())
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
        return quote(marketId, collateralAmount, null);
    }

    public LendingQuote quote(UUID marketId, BigInteger collateralAmount, UUID legalEntityId) {
        releaseGate.requireReleased();
        requireLendingEligible(legalEntityId);
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

        // All on-chain reads of a quote at one block number (obtained once), never a mix of heights/nodes.
        RepoMarketOnchainReader.PriceMark mark;
        BigInteger utilizationWad;
        BigInteger borrowRateWad;
        BigInteger availableLiquidity;
        try (RepoMarketOnchainReader.Pin pin = onchainReader.pinBlock(chainIdentifier)) {
            mark = onchainReader.price(
                    chainIdentifier, market.getPriceOracleAddress(), market.getCollateralTokenAddress());
            utilizationWad = onchainReader.utilization(chainIdentifier, market.getMarketAddress());
            borrowRateWad = onchainReader.borrowRate(chainIdentifier, market.getMarketAddress());
            availableLiquidity = onchainReader.availableLiquidity(
                    chainIdentifier, market.getMarketAddress(), market.getLoanTokenAddress());
        }

        BigInteger collateralValue = collateralAmount.multiply(mark.pricePerUnit());
        BigInteger collateralLimitedBorrow = collateralValue.multiply(BigInteger.valueOf(market.getMaxLtvBps()))
                .divide(BigInteger.valueOf(10_000));
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
        // 5B-09: a proxy/code swap after registration must not keep quoting as if nothing happened.
        if (market.getCodeHash() != null) {
            String current = onchainReader.codeHash(
                    resolveChainIdentifier(market.getChainConfigId()), market.getMarketAddress());
            if (!market.getCodeHash().equalsIgnoreCase(current)) {
                throw new IllegalStateException("Lending market " + market.getId()
                        + " runtime code changed since registration; re-verification required");
            }
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

    private MarketView view(
            LendingMarket market, Jurisdiction jurisdiction, String collateralAssetName, String collateralIsin,
            Boolean micarApplicable, DefiInteropModel defiInteropModel, LendingMarketStatus effectiveStatus,
            OnchainBinding binding) {
        // Null-safe: a deleted chain config must not break the catalog (chainId/chainName stay null).
        Optional<ChainConfig> chain = market.getChainConfigId() == null
                ? Optional.empty() : chainConfigRepository.findById(market.getChainConfigId());
        return new MarketView(market, jurisdiction, collateralAssetName, collateralIsin, micarApplicable,
                defiInteropModel, effectiveStatus, binding.riskParametersLegacy(), binding.operatorOrg(),
                binding.treasury(), pauseReason(market, effectiveStatus),
                chain.map(ChainConfig::getChainId).orElse(null),
                chain.map(c -> c.getDisplayName() != null ? c.getDisplayName() : c.getIdentifier()).orElse(null));
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

    private String pauseReason(LendingMarket market, LendingMarketStatus effective) {
        if (market.getStatus() != LendingMarketStatus.ACTIVE || effective == LendingMarketStatus.ACTIVE) return null;
        if (market.isCollateralShortfall()) return "COLLATERAL_SHORTFALL";
        if (!market.isBindingVerified()) return "BINDING_UNVERIFIED";
        return lastPauseCause.getOrDefault(market.getId(), "BORROW_PAUSED_ONCHAIN");
    }

    private final java.util.concurrent.ConcurrentHashMap<UUID, String> lastPauseCause =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        // 5B-10 / 5B-09: a recorded collateral shortfall or failed binding pauses the market for new
        // borrowing independently of the on-chain flag (the operator pauses on-chain separately).
        if (market.isCollateralShortfall() || !market.isBindingVerified()) {
            return LendingMarketStatus.PAUSED;
        }
        try {
            String chainIdentifier = resolveChainIdentifier(market.getChainConfigId());
            boolean paused = onchainReader.borrowPaused(chainIdentifier, market.getMarketAddress());
            lastPauseCause.remove(market.getId());
            return paused ? LendingMarketStatus.PAUSED : LendingMarketStatus.ACTIVE;
        } catch (RuntimeException e) {
            log.warn("borrowPaused read failed for market {}: {}", market.getMarketAddress(), e.getMessage());
            lastPauseCause.put(market.getId(), "CHAIN_READ_FAILED");
            return LendingMarketStatus.PAUSED;
        }
    }
}
