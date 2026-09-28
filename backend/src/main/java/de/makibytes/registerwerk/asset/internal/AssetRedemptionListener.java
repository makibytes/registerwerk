package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedemptionIncompleteEvent;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reacts to {@link AssetRedeemedEvent} by driving the on-chain side of redemption:
 * {@code AssetLifecycleService.redeem} only flips the DB status — without this listener the
 * tokens themselves are never burned/retired, so a "redeemed" asset would still show live
 * on-chain balances forever.
 *
 * <p>Only {@link #AUTOMATED_STANDARDS} (ERC-20/721/1155, via {@link TokenAdminPort}) are
 * dispatched automatically here — the same partial-coverage-with-honest-logging pattern already
 * used by {@code corporateactions.internal.CorporateActionSettlementListener}. ERC-3643 already
 * has its own manual forced-burn path ({@code Erc3643Controller}); DAML bonds redeem through
 * {@code CantonBondOperations.redeem} via the corporate-action REDEMPTION flow (dispatching a
 * second burn here would double-redeem them, so Canton is deliberately excluded); every other
 * standard (Solana, Starknet, Stellar) has no wired admin port yet and is logged for manual
 * operator follow-up rather than silently doing nothing.
 *
 * <p>T3-01: {@link HolderKind#NOMINEE_POOL} entries are never burnt (a pool contract's internal
 * accounting would break). For a bond, only wallets whose entry in the settled REDEMPTION/CALL
 * action is PAYABLE and settled are burnt — a holder who was not paid keeps their tokens. Every
 * active holder left unburnt (pool, unpaid, failed burn, no automated path) is logged at ERROR
 * and reported in one {@link AssetRedemptionIncompleteEvent} for operator follow-up.
 */
@Component
class AssetRedemptionListener {

    private static final Logger log = LoggerFactory.getLogger(AssetRedemptionListener.class);

    /** system actor for redemption burns triggered by the asset-status transition itself,
     *  not a direct operator API call. */
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private static final Set<TokenStandard> AUTOMATED_STANDARDS =
            EnumSet.of(TokenStandard.ERC20, TokenStandard.ERC721, TokenStandard.ERC1155);

    private final AssetRepository assetRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final AssetHolderRepository holderRepository;
    private final TokenAdminPort tokenAdminPort;
    private final RedemptionReadinessPort redemptionReadiness;
    private final ApplicationEventPublisher eventPublisher;

    AssetRedemptionListener(AssetRepository assetRepository,
                            AssetDeploymentRepository deploymentRepository,
                            AssetHolderRepository holderRepository,
                            TokenAdminPort tokenAdminPort,
                            RedemptionReadinessPort redemptionReadiness,
                            ApplicationEventPublisher eventPublisher) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.holderRepository = holderRepository;
        this.tokenAdminPort = tokenAdminPort;
        this.redemptionReadiness = redemptionReadiness;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onAssetRedeemed(AssetRedeemedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null) {
            log.warn("Asset disappeared before redemption burn could be dispatched: id={}", event.assetId());
            return;
        }

        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(event.assetId());
        if (deployments.isEmpty()) {
            log.info("Asset {} redeemed with no on-chain deployment (OnchainLevel.NONE?) — nothing to burn.",
                    event.assetId());
            return;
        }

        if (!AUTOMATED_STANDARDS.contains(asset.getTokenStandard())) {
            if (!asset.getTokenStandard().name().startsWith("DAML")) {
                List<Map<String, Object>> left = new ArrayList<>();
                for (AssetHolder holder : holderRepository.findActiveByAssetId(event.assetId())) {
                    if (holder.getNominalAmount() != null && holder.getNominalAmount().signum() > 0) {
                        left.add(unburnt(holder, "NO_AUTOMATED_BURN"));
                    }
                }
                reportIncomplete(event, left);
            }
            log.warn("Asset {} (standard={}) redeemed — no automated on-chain burn is wired for this "
                            + "standard yet; an operator must manually retire holder balances (ERC-3643: "
                            + "Erc3643Controller forced-burn; DAML bonds redeem via their own corporate-action "
                            + "flow and should NOT be double-redeemed here; other standards: no admin port yet).",
                    event.assetId(), asset.getTokenStandard());
            return;
        }

        AssetDeployment deployment = deployments.get(0);
        // Only currently-active register holders are burned; a removed holder's position
        // is already closed out of the register and is not this listener's concern.
        List<AssetHolder> holders = holderRepository.findActiveByAssetId(event.assetId());
        String legalBasis = "eWpG §26 Einziehung — Redemption of " + asset.getAssetNumber();

        // Bonds: only holders paid in the settled REDEMPTION/CALL are burnt (null = not a bond).
        Set<String> paidWallets = null;
        if (event.retirementActionId() != null) {
            paidWallets = redemptionReadiness.settledRetirementAction(event.assetId())
                    .map(RedemptionReadinessPort.SettledRetirement::paidWallets)
                    .orElse(Set.of());
        }

        List<Map<String, Object>> left = new ArrayList<>();
        int dispatched = 0;
        for (AssetHolder holder : holders) {
            BigDecimal nominal = holder.getNominalAmount();
            if (nominal == null || nominal.signum() <= 0) {
                continue;
            }
            if (holder.getHolderKind() == HolderKind.NOMINEE_POOL) {
                left.add(unburnt(holder, "NOMINEE_POOL"));
                continue;
            }
            if (paidWallets != null && !paidWallets.contains(AddressNormalizer.normalize(holder.getWalletAddress()))) {
                left.add(unburnt(holder, "NOT_PAID_IN_SETTLED_REDEMPTION"));
                continue;
            }
            try {
                UUID actorId = event.actorId() != null ? event.actorId() : SYSTEM_ACTOR;
                String actorRole = event.actorRole() != null ? event.actorRole() : "SYSTEM";
                BigInteger amount = nominal.setScale(0, RoundingMode.DOWN).toBigInteger();
                if (asset.getTokenStandard() == TokenStandard.ERC1155) {
                    // TokenAdminPort.forceBurn now rejects ERC-1155 outright (it would only ever
                    // burn token id 0, silently ignoring the real tranche a manual BaFin/court-order
                    // caller might mean). AssetHolder does not yet track per-id balances, so full
                    // redemption here has always meant "burn id 0" — forceBurnSingle with an
                    // explicit id 0 keeps that exact, pre-existing behavior.
                    tokenAdminPort.forceBurnSingle(deployment.getId(), holder.getWalletAddress(),
                            BigInteger.ZERO, amount, legalBasis, actorId, actorRole);
                } else {
                    tokenAdminPort.forceBurn(deployment.getId(), holder.getWalletAddress(),
                            amount, legalBasis, actorId, actorRole);
                }
                dispatched++;
            } catch (Exception e) {
                log.error("Redemption burn failed for asset={} holder wallet={}: {}",
                        event.assetId(), holder.getWalletAddress(), e.getMessage());
                left.add(unburnt(holder, "BURN_FAILED: " + e.getMessage()));
            }
        }
        log.info("Asset {} redemption: dispatched {} of {} holder burns.", event.assetId(), dispatched, holders.size());
        reportIncomplete(event, left);
    }

    private static Map<String, Object> unburnt(AssetHolder holder, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("walletAddress", String.valueOf(holder.getWalletAddress()));
        m.put("nominal", holder.getNominalAmount().toPlainString());
        m.put("reason", reason);
        return m;
    }

    private void reportIncomplete(AssetRedeemedEvent event, List<Map<String, Object>> left) {
        if (left.isEmpty()) {
            return;
        }
        log.error("REDEMPTION INCOMPLETE: asset={} is REDEEMED but {} active register entr(y/ies) keep live "
                + "tokens on-chain — operator follow-up required: {}", event.assetId(), left.size(), left);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("unburnt", left);
        if (event.retirementActionId() != null) {
            details.put("retirementCorporateActionId", event.retirementActionId().toString());
        }
        eventPublisher.publishEvent(new AssetRedemptionIncompleteEvent(event.assetId(), details));
    }
}
