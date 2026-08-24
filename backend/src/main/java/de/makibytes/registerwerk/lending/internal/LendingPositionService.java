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
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            String chainIdentifier;
            try {
                marketService.requireOperational(market);
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
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            String chainIdentifier;
            try {
                marketService.requireOperational(market);
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

    private Optional<LendingPosition> refreshPosition(LendingMarket market, String chainIdentifier, String walletAddress) {
        Optional<LendingPosition> existing =
                positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress);

        BigInteger collateralAmount =
                onchainReader.positionCollateralAmount(chainIdentifier, market.getMarketAddress(), walletAddress);
        BigInteger debt = onchainReader.debtOf(chainIdentifier, market.getMarketAddress(), walletAddress);

        // Never interacted with this market and nothing cached yet — nothing worth persisting.
        // If a row already exists, we must still update it below (e.g. a full repay driving
        // both amounts to zero has to flip the cached status to CLOSED, not be skipped).
        if (existing.isEmpty() && collateralAmount.signum() == 0 && debt.signum() == 0) {
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
