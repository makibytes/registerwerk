package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.indexer.api.DemoNomineePoolEntity;
import de.makibytes.registerwerk.indexer.events.NomineePoolHolderRegisteredEvent;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Enters pool contracts into an asset's register as {@link HolderKind#NOMINEE_POOL} holders
 * (T2-18). Pledging collateral into an {@code EwpgRepoMarket}, escrowing into DvP, or parking
 * inventory at a desk/facility moves units to a contract address that has no investor identity;
 * without a holder row for it, {@code HolderDataService} refuses to reconcile the whole asset.
 *
 * <p>The row is held in the operator's legal entity ({@code registerwerk.sync.nominee-pool-entity-id}
 * unless the operator names another) and is chain-derived, so the regular sync keeps its balance.
 * Who is economically entitled to coupons/redemption on pooled units is undecided (PARK-T2-18):
 * corporate-action entries for these rows are snapshotted as {@code HELD_LOOK_THROUGH} and never
 * settled automatically.
 */
@Service
public class NomineePoolHolderService {

    private static final Logger log = LoggerFactory.getLogger(NomineePoolHolderService.class);

    public static final String LENDING_MARKET = "LENDING_MARKET";
    public static final Set<String> POOL_KINDS = Set.of(LENDING_MARKET, "DVP_ESCROW", "DESK", "FACILITY");

    private final AssetHolderRepository holderRepository;
    private final AssetLookupPort assetLookupPort;
    private final LendingMarketRepository lendingMarketRepository;
    private final ApplicationEventPublisher events;
    private final UUID defaultNomineeEntityId;
    private final DemoNomineePoolEntity demoNomineePoolEntity;

    NomineePoolHolderService(AssetHolderRepository holderRepository,
                             AssetLookupPort assetLookupPort,
                             LendingMarketRepository lendingMarketRepository,
                             ApplicationEventPublisher events,
                             DemoNomineePoolEntity demoNomineePoolEntity,
                             @Value("${registerwerk.sync.nominee-pool-entity-id:}") String defaultNomineeEntityId) {
        this.holderRepository = holderRepository;
        this.assetLookupPort = assetLookupPort;
        this.lendingMarketRepository = lendingMarketRepository;
        this.events = events;
        this.demoNomineePoolEntity = demoNomineePoolEntity;
        this.defaultNomineeEntityId = defaultNomineeEntityId == null || defaultNomineeEntityId.isBlank()
                ? null : UUID.fromString(defaultNomineeEntityId.trim());
    }

    /**
     * The legal entity pool rows are held in, if any: the configured operator entity, else — only
     * when the demo seeder created one ({@link DemoNomineePoolEntity}, never in production) — the
     * demo nominee-pool entity.
     */
    public Optional<UUID> defaultNomineeEntityId() {
        if (defaultNomineeEntityId != null) {
            return Optional.of(defaultNomineeEntityId);
        }
        return demoNomineePoolEntity.get();
    }

    /**
     * Registers {@code walletAddress} as a nominee-pool holder of {@code assetId}. Idempotent for
     * an address that already is a nominee pool of this asset; refused for an address that is an
     * investor's register entry (that would silently re-attribute the investor's position).
     *
     * @param investorId the legal entity holding the pool row; null = the configured default
     */
    @Transactional
    public AssetHolder register(UUID assetId, String walletAddress, String poolKind, UUID investorId,
                                UUID actorId, String actorRole) {
        if (walletAddress == null || walletAddress.isBlank() || walletAddress.trim().length() > 66) {
            throw new IllegalArgumentException("walletAddress is required (max 66 characters)");
        }
        if (!POOL_KINDS.contains(poolKind)) {
            throw new IllegalArgumentException("poolKind must be one of " + POOL_KINDS);
        }
        assetLookupPort.findById(assetId).orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        UUID holderEntity = investorId != null ? investorId : defaultNomineeEntityId().orElse(null);
        if (holderEntity == null) {
            throw new IllegalArgumentException("No legal entity for the nominee-pool row: pass investorId or "
                    + "configure registerwerk.sync.nominee-pool-entity-id (the operator's legal entity)");
        }
        String wallet = walletAddress.trim();
        String key = wallet.toLowerCase(Locale.ROOT);

        Optional<AssetHolder> existing = holderRepository.findByAssetId(assetId, Pageable.unpaged()).stream()
                .filter(h -> h.getWalletAddress() != null && h.getWalletAddress().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
        if (existing.isPresent()) {
            if (existing.get().getHolderKind() == HolderKind.NOMINEE_POOL) {
                return existing.get();
            }
            throw new IllegalArgumentException("Wallet " + wallet + " is already an investor register entry of asset "
                    + assetId + " — map it there instead of registering it as a pool");
        }

        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(holderEntity);
        holder.setWalletAddress(wallet);
        holder.setHolderKind(HolderKind.NOMINEE_POOL);
        holder.setEntryType(EntryType.COLLECTIVE);
        holder.setNominalAmount(BigDecimal.ZERO);
        // Chain-derived from the start: the balance is whatever the sync counts for the pool.
        holder.setChainDerived(true);
        holder.setIsConsumer(false);
        holder.setThirdPartyRights("Nominee pool (" + poolKind + "): units held on behalf of third parties");
        AssetHolder saved = holderRepository.save(holder);

        events.publishEvent(new NomineePoolHolderRegisteredEvent(saved.getId(), assetId, wallet, poolKind,
                holderEntity, actorId, actorRole));
        log.info("Nominee-pool holder registered: asset={} wallet={} kind={}", assetId, wallet, poolKind);
        return saved;
    }

    /**
     * Registers the market contract of one lending market as a nominee pool of its collateral
     * asset. Without a configured nominee entity this logs an error and does nothing — the next
     * sync then marks the asset BLOCKED naming the market address, which the operator resolves
     * through the manual action.
     *
     * @return true when a row exists for the market afterwards
     */
    @Transactional
    public boolean registerLendingMarket(UUID marketId, UUID actorId, String actorRole) {
        LendingMarket market = lendingMarketRepository.findById(marketId)
                .orElseThrow(() -> new EntityNotFoundException("LendingMarket", marketId));
        if (defaultNomineeEntityId().isEmpty()) {
            log.error("Lending market {} ({}) was not entered as a nominee-pool holder of asset {}: "
                            + "registerwerk.sync.nominee-pool-entity-id is not configured. Holder sync for the "
                            + "asset will be BLOCKED once collateral is pledged — register the pool address manually.",
                    marketId, market.getMarketAddress(), market.getCollateralAssetId());
            return false;
        }
        register(market.getCollateralAssetId(), market.getMarketAddress(), LENDING_MARKET, null, actorId, actorRole);
        return true;
    }

    /**
     * Backfill for markets registered before T2-18: enters every registered lending market's
     * contract as a nominee pool of its collateral asset. Idempotent.
     *
     * @return number of markets that now have a nominee-pool row
     */
    @Transactional
    public int backfillLendingMarkets(UUID actorId, String actorRole) {
        int done = 0;
        for (LendingMarket market : lendingMarketRepository.findAll()) {
            try {
                if (registerLendingMarket(market.getId(), actorId, actorRole)) {
                    done++;
                }
            } catch (IllegalArgumentException e) {
                log.error("Nominee-pool backfill skipped lending market {}: {}", market.getId(), e.getMessage());
            }
        }
        return done;
    }
}
