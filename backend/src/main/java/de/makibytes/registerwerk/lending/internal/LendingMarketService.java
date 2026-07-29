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
public class LendingMarketService {

    private static final Logger log = LoggerFactory.getLogger(LendingMarketService.class);

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
     */
    public record MarketView(
            LendingMarket market, Jurisdiction jurisdiction, String collateralAssetName, String collateralIsin,
            Boolean micarApplicable, DefiInteropModel defiInteropModel, LendingMarketStatus effectiveStatus) {}

    public MarketView registerMarket(
            UUID chainConfigId, String marketAddress, String vaultAddress, UUID collateralAssetId,
            String collateralTokenAddress, String loanTokenAddress, String loanRailCode,
            int lltvBps, int liquidationBonusBps, BigInteger baseRateWad, BigInteger slopeWad,
            String priceOracleAddress, UUID actorId, String actorRole) {

        releaseGate.requireReleased();

        if (marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress)) {
            throw new IllegalArgumentException(
                    "Market " + marketAddress + " is already registered on this chain");
        }
        chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));

        LendingMarket market = new LendingMarket();
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setVaultAddress(vaultAddress);
        market.setCollateralAssetId(collateralAssetId);
        market.setCollateralTokenAddress(collateralTokenAddress);
        market.setLoanTokenAddress(loanTokenAddress);
        market.setLoanRailCode(loanRailCode);
        market.setLltvBps(lltvBps);
        market.setLiquidationBonusBps(liquidationBonusBps);
        market.setBaseRateWad(baseRateWad);
        market.setSlopeWad(slopeWad);
        market.setPriceOracleAddress(priceOracleAddress);
        market.setRegisteredBy(actorId);
        market = marketRepository.save(market);

        eventPublisher.publishEvent(new LendingMarketRegisteredEvent(market.getId(), actorId, actorRole,
                Map.of("marketAddress", marketAddress, "lltvBps", lltvBps)));

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
        String chainIdentifier = resolveChainIdentifier(market.getChainConfigId());

        RepoMarketOnchainReader.PriceMark mark = onchainReader.price(
                chainIdentifier, market.getPriceOracleAddress(), market.getCollateralTokenAddress());
        BigInteger utilizationWad = onchainReader.utilization(chainIdentifier, market.getMarketAddress());
        BigInteger borrowRateWad = onchainReader.borrowRate(chainIdentifier, market.getMarketAddress());

        BigInteger collateralValue = collateralAmount.multiply(mark.pricePerUnit());
        BigInteger maxBorrow = collateralValue.multiply(BigInteger.valueOf(market.getLltvBps()))
                .divide(BigInteger.valueOf(10_000));

        return new LendingQuote(
                marketId, collateralAmount, mark.pricePerUnit(), mark.updatedAt(),
                maxBorrow, market.getLltvBps(), utilizationWad, borrowRateWad);
    }

    public record LendingQuote(
            UUID marketId, BigInteger collateralAmount, BigInteger pricePerUnit, BigInteger priceUpdatedAt,
            BigInteger maxBorrowAmount, int lltvBps, BigInteger utilizationWad, BigInteger borrowRateWad) {}

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
        if (market.getCollateralAssetId() == null) {
            return new MarketView(market, null, null, null, null, null, effectiveStatus);
        }
        Optional<Asset> asset = assetRepository.findById(market.getCollateralAssetId());
        return asset
                .map(a -> {
                    Jurisdiction jurisdiction = a.getJurisdiction();
                    if (jurisdiction == null) {
                        return new MarketView(market, null, a.getName(), a.getIsin(), null, null, effectiveStatus);
                    }
                    var compliance = jurisdictionConfig.getProfile(jurisdiction).compliance();
                    return new MarketView(market, jurisdiction, a.getName(), a.getIsin(),
                            compliance.micarApplicable(), compliance.defiInteropModel(), effectiveStatus);
                })
                .orElseGet(() -> new MarketView(market, null, null, null, null, null, effectiveStatus));
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
