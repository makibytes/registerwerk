package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingPosition;
import de.makibytes.registerwerk.lending.api.LendingPositionRepository;
import de.makibytes.registerwerk.lending.api.LendingPositionStatus;
import de.makibytes.registerwerk.lending.api.LendingSupplyPosition;
import de.makibytes.registerwerk.lending.api.LendingSupplyPositionRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refreshes and serves the {@link LendingPosition}/{@link LendingSupplyPosition} read-model by
 * reading live on-chain state for every wallet the effective legal entity has bound (via
 * {@code orgidentity.OrgMemberWallet} — the same binding used for org identity/manifest
 * signing) against every {@code ACTIVE} {@link LendingMarket}. Refresh happens synchronously on
 * request rather than via a background poller: this is a reference implementation for a
 * handful of markets and wallets per user, and correctness (not latency) is what matters for a
 * trader about to act on a health factor.
 */
@Service
@Transactional
public class LendingPositionService {

    private static final Logger log = LoggerFactory.getLogger(LendingPositionService.class);

    private final LendingMarketRepository marketRepository;
    private final LendingPositionRepository positionRepository;
    private final LendingSupplyPositionRepository supplyPositionRepository;
    private final OrgMemberWalletRepository memberWalletRepository;
    private final RepoMarketOnchainReader onchainReader;
    private final LendingMarketService marketService;
    private final RepoMarketEventReader eventReader;
    private final LendingReleaseGate releaseGate;

    LendingPositionService(
            LendingMarketRepository marketRepository,
            LendingPositionRepository positionRepository,
            LendingSupplyPositionRepository supplyPositionRepository,
            OrgMemberWalletRepository memberWalletRepository,
            RepoMarketOnchainReader onchainReader,
            LendingMarketService marketService,
            RepoMarketEventReader eventReader,
            LendingReleaseGate releaseGate) {
        this.marketRepository = marketRepository;
        this.positionRepository = positionRepository;
        this.supplyPositionRepository = supplyPositionRepository;
        this.memberWalletRepository = memberWalletRepository;
        this.onchainReader = onchainReader;
        this.marketService = marketService;
        this.eventReader = eventReader;
        this.releaseGate = releaseGate;
    }

    public List<LendingPosition> refreshAndListMyPositions(UUID legalEntityId) {
        releaseGate.requireReleased();
        List<OrgMemberWallet> wallets = activeWallets(legalEntityId);
        if (wallets.isEmpty()) return List.of();

        List<LendingPosition> results = new ArrayList<>();
        // Deliberately not limited to operational markets: a market paused on-chain (for example
        // a legacy one, see LendingMarketService.MarketView#riskParametersLegacy) still has
        // borrowers who must see their loans to repay, claim collateral or claim surplus.
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            String chainIdentifier;
            try {
                chainIdentifier = marketService.resolveChainIdentifier(market.getChainConfigId());
            } catch (RuntimeException e) {
                log.warn("Skipping unavailable lending market {} while refreshing positions: {}",
                        market.getId(), e.getMessage());
                continue;
            }
            for (OrgMemberWallet wallet : wallets) {
                if (!wallet.getChainConfigId().equals(market.getChainConfigId())) continue;
                try {
                    refreshPosition(market, chainIdentifier, wallet.getWalletAddress()).ifPresent(results::add);
                } catch (RuntimeException e) {
                    log.warn("Unable to refresh lending position for market {} wallet {}: {}",
                            market.getId(), wallet.getWalletAddress(), e.getMessage());
                    // 5B-11: keep the previous values but say so, instead of hiding the row or zeroing it.
                    markStale(market, wallet.getWalletAddress(), e).ifPresent(results::add);
                }
            }
        }
        return results;
    }

    public List<LendingSupplyPosition> refreshAndListMySupplyPositions(UUID legalEntityId) {
        releaseGate.requireReleased();
        List<OrgMemberWallet> wallets = activeWallets(legalEntityId);
        if (wallets.isEmpty()) return List.of();

        List<LendingSupplyPosition> results = new ArrayList<>();
        // Not limited to operational markets either: withdrawing stays available when paused.
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            String chainIdentifier;
            try {
                chainIdentifier = marketService.resolveChainIdentifier(market.getChainConfigId());
            } catch (RuntimeException e) {
                log.warn("Skipping unavailable lending market {} while refreshing supply positions: {}",
                        market.getId(), e.getMessage());
                continue;
            }
            for (OrgMemberWallet wallet : wallets) {
                if (!wallet.getChainConfigId().equals(market.getChainConfigId())) continue;
                try {
                    refreshSupplyPosition(market, chainIdentifier, wallet.getWalletAddress()).ifPresent(results::add);
                } catch (RuntimeException e) {
                    log.warn("Unable to refresh lending supply position for market {} wallet {}: {}",
                            market.getId(), wallet.getWalletAddress(), e.getMessage());
                }
            }
        }
        return results;
    }

    private Optional<LendingPosition> markStale(LendingMarket market, String walletAddress, RuntimeException cause) {
        return positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress).map(p -> {
            p.setSyncStale(true);
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            p.setLastSyncError(message.substring(0, Math.min(message.length(), 500)));
            return positionRepository.save(p);
        });
    }

    /** Ids of markets whose collateral balance is below their recorded total (positions unverified). */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> shortfallMarketIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        marketRepository.findByCollateralShortfallTrue().forEach(m -> ids.add(m.getId()));
        return ids;
    }

    private Optional<LendingPosition> refreshPosition(LendingMarket market, String chainIdentifier, String walletAddress) {
        // One block number for every read of this refresh (collateral, debt, health factor, surplus).
        try (RepoMarketOnchainReader.Pin pin = onchainReader.pinBlock(chainIdentifier)) {
            return refreshPositionPinned(market, chainIdentifier, walletAddress);
        }
    }

    private Optional<LendingPosition> refreshPositionPinned(LendingMarket market, String chainIdentifier, String walletAddress) {
        Optional<LendingPosition> existing =
                positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress);

        BigInteger collateralAmount =
                onchainReader.positionCollateralAmount(chainIdentifier, market.getMarketAddress(), walletAddress);
        BigInteger debt = onchainReader.debtOf(chainIdentifier, market.getMarketAddress(), walletAddress);
        BigInteger surplus = liquidationSurplus(market, chainIdentifier, walletAddress);

        // Never interacted with this market and nothing cached yet — nothing worth persisting.
        // If a row already exists, we must still update it below (e.g. a full repay driving
        // both amounts to zero has to flip the cached status to CLOSED, not be skipped).
        if (existing.isEmpty() && collateralAmount.signum() == 0 && debt.signum() == 0 && surplus.signum() == 0) {
            return Optional.empty();
        }

        BigInteger healthFactor = null;
        Boolean healthFactorReliable = null;
        if (debt.signum() > 0) {
            try {
                RepoMarketOnchainReader.HealthFactorReading reading =
                        onchainReader.healthFactor(chainIdentifier, market.getMarketAddress(), walletAddress);
                healthFactor = reading.factor();
                healthFactorReliable = reading.priceReliable();
            } catch (RuntimeException e) {
                healthFactorReliable = false;
                log.warn("Unable to verify lending health factor for market {} wallet {}: {}",
                        market.getId(), walletAddress, e.getMessage());
            }
        }

        boolean wasOpen = existing.isPresent() && existing.get().getStatus() == LendingPositionStatus.OPEN;

        LendingPosition position = existing.orElseGet(LendingPosition::new);
        position.setMarketId(market.getId());
        position.setWalletAddress(walletAddress);
        position.setCollateralAmount(collateralAmount);
        position.setCurrentDebt(debt);
        position.setHealthFactorWad(healthFactor);
        position.setHealthFactorReliable(healthFactorReliable);
        position.setLiquidationSurplus(surplus);
        position.setSyncStale(false);
        position.setLastSyncError(null);
        if (debt.signum() > 0) {
            position.setStatus(LendingPositionStatus.OPEN);
        } else {
            // Both repay and liquidation can end at debt == 0. Graph Node may provide an
            // operational hint, but it does not prove canonical inclusion or finality and must
            // not create a durable LIQUIDATED classification. Existing LIQUIDATED rows have no
            // stored provenance that could distinguish canonical evidence from the former
            // subgraph-derived path, so refresh also fails them closed.
            if (wasOpen) observeClosingHint(market, walletAddress);
            position.setStatus(LendingPositionStatus.CLOSED);
        }
        position.setLastSyncedAt(Instant.now());
        return Optional.of(positionRepository.save(position));
    }

    /**
     * Liquidation surplus owed to the wallet. A market without {@code surplusOf} (probed at
     * registration) deliberately reads as zero. On a supporting market a failed read is an error
     * ({@link PositionRefreshException}): the caller keeps the previous value and marks the row stale.
     */
    private BigInteger liquidationSurplus(LendingMarket market, String chainIdentifier, String walletAddress) {
        if (!market.isSurplusSupported()) {
            return BigInteger.ZERO;
        }
        try {
            BigInteger surplus = onchainReader.liquidationSurplus(chainIdentifier, market.getMarketAddress(), walletAddress);
            return surplus != null ? surplus : BigInteger.ZERO;
        } catch (PositionRefreshException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PositionRefreshException("Liquidation surplus unreadable for market "
                    + market.getMarketAddress() + ": " + e.getMessage(), e);
        }
    }

    private void observeClosingHint(LendingMarket market, String walletAddress) {
        try {
            var chainConfig = marketService.resolveChainConfig(market.getChainConfigId());
            eventReader.lastClosingEventHint(chainConfig, market.getMarketAddress(), walletAddress)
                    .ifPresent(hint -> log.debug(
                            "Observed unfinalized repo-market closing hint type={} projectionStatus={} "
                                    + "for market={} wallet={}; durable status remains CLOSED",
                            hint.eventType(), hint.projectionStatus(), market.getMarketAddress(), walletAddress));
        } catch (RuntimeException e) {
            log.warn("repoMarketEvents lookup failed for market {} wallet {}: {}",
                    market.getMarketAddress(), walletAddress, e.getMessage());
        }
    }

    private Optional<LendingSupplyPosition> refreshSupplyPosition(
            LendingMarket market, String chainIdentifier, String walletAddress) {
        try (RepoMarketOnchainReader.Pin pin = onchainReader.pinBlock(chainIdentifier)) {
            return refreshSupplyPositionPinned(market, chainIdentifier, walletAddress);
        }
    }

    private Optional<LendingSupplyPosition> refreshSupplyPositionPinned(
            LendingMarket market, String chainIdentifier, String walletAddress) {
        Optional<LendingSupplyPosition> existing =
                supplyPositionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress);

        BigInteger claim = onchainReader.supplyBalanceOf(chainIdentifier, market.getMarketAddress(), walletAddress);
        if (existing.isEmpty() && claim.signum() == 0) {
            return Optional.empty();
        }

        LendingSupplyPosition position = existing.orElseGet(LendingSupplyPosition::new);
        position.setMarketId(market.getId());
        position.setWalletAddress(walletAddress);
        position.setCurrentClaim(claim);
        position.setLastSyncedAt(Instant.now());
        return Optional.of(supplyPositionRepository.save(position));
    }

    private List<OrgMemberWallet> activeWallets(UUID legalEntityId) {
        if (legalEntityId == null) return List.of();
        return memberWalletRepository.findActiveByLegalEntityId(legalEntityId).stream()
                .filter(w -> w.getStatus() == MemberWalletStatus.ACTIVE)
                .toList();
    }
}
