package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedemptionIncompleteEvent;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reacts to {@link AssetRedeemedEvent} by driving the on-chain side of a redemption:
 * {@code AssetLifecycleService.redeem} only moves the asset to {@code REDEMPTION_PENDING} — without this listener the
 * tokens themselves are never burned/retired.
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
 *
 * <p>Wave 0b C7:
 * <ul>
 *   <li><b>Amount.</b> A wallet is burnt {@code min(its current balance, its nominal at the record date)} - the units
 *       the redemption paid for; units bought after the record date are not destroyed unpaid. A non-bond has no record
 *       date: its current balance.</li>
 *   <li><b>Every deployment.</b> The burn is spread over EVERY deployment of the asset (each capped at the wallet's
 *       balance on that deployment), not just the first one.</li>
 *   <li><b>Durable and idempotent.</b> Each burn goes through the durable outbox (persisted in its own database
 *       transaction, broadcast after commit) and is keyed {@code (asset, deployment, wallet)}: a redelivered event, or
 *       a resume, never burns the same wallet twice; only FAILED burns are retried.</li>
 *   <li><b>Outcome, not submission.</b> The asset stays REDEMPTION_PENDING until every burn is confirmed
 *       ({@link RedemptionBurnFinalizer}); a burn that cannot even be submitted is recorded FAILED and reported.</li>
 * </ul>
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
    private final AssetRedemptionBurnRepository burns;
    private final IndexedTransferLookup indexed;
    private final AssetLifecycleService lifecycle;
    private final IsolatedTransactionExecutor isolated;

    AssetRedemptionListener(AssetRepository assetRepository,
                            AssetDeploymentRepository deploymentRepository,
                            AssetHolderRepository holderRepository,
                            TokenAdminPort tokenAdminPort,
                            RedemptionReadinessPort redemptionReadiness,
                            ApplicationEventPublisher eventPublisher,
                            AssetRedemptionBurnRepository burns,
                            IndexedTransferLookup indexed,
                            AssetLifecycleService lifecycle,
                            IsolatedTransactionExecutor isolated) {
        this.assetRepository = assetRepository;
        this.deploymentRepository = deploymentRepository;
        this.holderRepository = holderRepository;
        this.tokenAdminPort = tokenAdminPort;
        this.redemptionReadiness = redemptionReadiness;
        this.eventPublisher = eventPublisher;
        this.burns = burns;
        this.indexed = indexed;
        this.lifecycle = lifecycle;
        this.isolated = isolated;
    }

    @ApplicationModuleListener
    void onAssetRedeemed(AssetRedeemedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null) {
            log.warn("Asset disappeared before redemption burn could be dispatched: id={}", event.assetId());
            return;
        }
        if (asset.getStatus() != AssetStatus.REDEMPTION_PENDING) {
            log.info("Asset {} is {} - no redemption to drive (already completed or not started).",
                    event.assetId(), asset.getStatus());
            return;
        }

        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(event.assetId()).stream()
                .filter(d -> d.getDeploymentStatus() != AssetDeployment.DeploymentStatus.FAILED)
                .sorted(Comparator.comparing(AssetDeployment::getDeployedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (deployments.isEmpty()) {
            log.info("Asset {} redeemed with no on-chain deployment (OnchainLevel.NONE?) — nothing to burn.",
                    event.assetId());
            lifecycle.completeRedemption(event.assetId(), 0);
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
            lifecycle.completeRedemption(event.assetId(), 0);
            return;
        }

        // Only currently-active register holders are burned; a removed holder's position
        // is already closed out of the register and is not this listener's concern.
        List<AssetHolder> holders = holderRepository.findActiveByAssetId(event.assetId());
        String legalBasis = "eWpG §26 Einziehung — Redemption of " + asset.getAssetNumber();

        // Bonds: only holders paid in the settled REDEMPTION/CALL are burnt (null = not a bond), and only up to the
        // units they held at the record date - what the redemption paid for.
        Set<String> paidWallets = null;
        Map<String, BigDecimal> nominalAtRecord = Map.of();
        if (event.retirementActionId() != null) {
            Optional<RedemptionReadinessPort.SettledRetirement> retirement =
                    redemptionReadiness.settledRetirementAction(event.assetId());
            paidWallets = retirement.map(RedemptionReadinessPort.SettledRetirement::paidWallets).orElse(Set.of());
            nominalAtRecord = retirement.map(RedemptionReadinessPort.SettledRetirement::nominalAtRecord).orElse(Map.of());
        }

        UUID actorId = event.actorId() != null ? event.actorId() : SYSTEM_ACTOR;
        String actorRole = event.actorRole() != null ? event.actorRole() : "SYSTEM";
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
            String wallet = AddressNormalizer.normalize(holder.getWalletAddress());
            if (paidWallets != null && !paidWallets.contains(wallet)) {
                left.add(unburnt(holder, "NOT_PAID_IN_SETTLED_REDEMPTION"));
                continue;
            }
            BigDecimal toBurn = nominal;
            BigDecimal atRecord = nominalAtRecord.get(wallet);
            if (paidWallets != null && atRecord != null && atRecord.compareTo(toBurn) < 0) {
                toBurn = atRecord; // never burn units that were not paid for
            }
            List<Planned> plans = plan(deployments, holder.getWalletAddress(), toBurn);
            for (Planned plan : plans) {
                String failure = dispatch(asset, plan, holder.getWalletAddress(), legalBasis, actorId, actorRole);
                if (failure == null) {
                    dispatched++;
                } else {
                    left.add(unburnt(holder, failure));
                }
            }
            BigInteger planned = plans.stream().map(Planned::amount).reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger wanted = toBurn.setScale(0, RoundingMode.DOWN).toBigInteger();
            if (planned.compareTo(wanted) < 0) {
                left.add(unburnt(holder, "BALANCE_NOT_FOUND_ON_DEPLOYMENTS: " + wanted.subtract(planned)
                        + " unit(s) of the paid nominal are not on any indexed deployment balance"));
            }
        }
        log.info("Asset {} redemption: dispatched {} burn(s) for {} holder(s) over {} deployment(s).",
                event.assetId(), dispatched, holders.size(), deployments.size());
        reportIncomplete(event, left);

        List<AssetRedemptionBurn> all = burns.findByAssetId(event.assetId());
        if (all.stream().allMatch(b -> b.getStatus() == AssetRedemptionBurn.Status.CONFIRMED)) {
            lifecycle.completeRedemption(event.assetId(), all.size());
        }
    }

    /** One burn: this wallet loses {@code amount} on this deployment. */
    private record Planned(AssetDeployment deployment, BigInteger amount) {
    }

    /**
     * Spreads {@code toBurn} over the asset's deployments: capped at the wallet's balance on each. A single
     * deployment needs no netting (the register nominal IS its balance); several do.
     */
    private List<Planned> plan(List<AssetDeployment> deployments, String wallet, BigDecimal toBurn) {
        BigInteger remaining = toBurn.setScale(0, RoundingMode.DOWN).toBigInteger();
        List<Planned> plans = new ArrayList<>();
        if (deployments.size() == 1) {
            if (remaining.signum() > 0) {
                plans.add(new Planned(deployments.getFirst(), remaining));
            }
            return plans;
        }
        for (AssetDeployment deployment : deployments) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigInteger balance = indexed.finalizedBalance(deployment.getId(), wallet).setScale(0, RoundingMode.DOWN).toBigInteger();
            BigInteger take = balance.min(remaining);
            if (take.signum() > 0) {
                plans.add(new Planned(deployment, take));
                remaining = remaining.subtract(take);
            }
        }
        return plans;
    }

    /**
     * Submits one burn in its OWN transaction (the token admin service joins the caller's transaction, so a refusal
     * thrown from it would otherwise poison this listener's). The row is the idempotency key: an existing SUBMITTED or
     * CONFIRMED row is never submitted again; a FAILED one is re-driven.
     *
     * @return null when submitted (or already tracked), else why it could not be
     */
    private String dispatch(Asset asset, Planned plan, String wallet, String legalBasis, UUID actorId, String actorRole) {
        UUID deploymentId = plan.deployment().getId();
        Optional<AssetRedemptionBurn> existing = burns.findByAssetIdAndDeploymentIdAndWalletAddress(
                asset.getId(), deploymentId, AddressNormalizer.normalize(wallet));
        if (existing.isPresent() && existing.get().getStatus() != AssetRedemptionBurn.Status.FAILED) {
            return null; // submitted or confirmed already: never burn twice
        }
        try {
            isolated.run(() -> submit(asset, plan, wallet, legalBasis, actorId, actorRole));
            return null;
        } catch (Exception e) {
            log.error("Redemption burn failed for asset={} holder wallet={} deployment={}: {}",
                    asset.getId(), wallet, deploymentId, e.getMessage());
            String reason = "BURN_FAILED: " + e.getMessage();
            try {
                isolated.run(() -> recordFailure(asset.getId(), deploymentId, wallet, plan.amount(), reason));
            } catch (Exception recordingFailed) {
                log.error("Could not record the failed burn of {} on {}: {}", wallet, deploymentId, recordingFailed.getMessage());
            }
            return reason;
        }
    }

    private void submit(Asset asset, Planned plan, String wallet, String legalBasis, UUID actorId, String actorRole) {
        UUID deploymentId = plan.deployment().getId();
        String key = AddressNormalizer.normalize(wallet);
        AssetRedemptionBurn row = burns.findByAssetIdAndDeploymentIdAndWalletAddress(asset.getId(), deploymentId, key)
                .orElseGet(AssetRedemptionBurn::new);
        row.setAssetId(asset.getId());
        row.setDeploymentId(deploymentId);
        row.setWalletAddress(key);
        row.setAmount(new BigDecimal(plan.amount()));
        UUID txId;
        if (asset.getTokenStandard() == TokenStandard.ERC1155) {
            // TokenAdminPort.forceBurn now rejects ERC-1155 outright (it would only ever
            // burn token id 0, silently ignoring the real tranche a manual BaFin/court-order
            // caller might mean). AssetHolder does not yet track per-id balances, so full
            // redemption here has always meant "burn id 0" — forceBurnSingle with an
            // explicit id 0 keeps that exact, pre-existing behavior.
            txId = tokenAdminPort.forceBurnSingle(deploymentId, wallet, BigInteger.ZERO, plan.amount(),
                    legalBasis, actorId, actorRole);
        } else {
            txId = tokenAdminPort.forceBurn(deploymentId, wallet, plan.amount(), legalBasis, actorId, actorRole);
        }
        row.setTxId(txId);
        row.setStatus(AssetRedemptionBurn.Status.SUBMITTED);
        row.setFailureReason(null);
        burns.save(row);
    }

    private void recordFailure(UUID assetId, UUID deploymentId, String wallet, BigInteger amount, String reason) {
        String key = AddressNormalizer.normalize(wallet);
        AssetRedemptionBurn row = burns.findByAssetIdAndDeploymentIdAndWalletAddress(assetId, deploymentId, key)
                .orElseGet(AssetRedemptionBurn::new);
        row.setAssetId(assetId);
        row.setDeploymentId(deploymentId);
        row.setWalletAddress(key);
        row.setAmount(new BigDecimal(amount));
        row.setTxId(null);
        row.setStatus(AssetRedemptionBurn.Status.FAILED);
        row.setFailureReason(reason);
        burns.save(row);
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
        log.error("REDEMPTION INCOMPLETE: asset={} has {} active register entr(y/ies) whose tokens stay live "
                + "on-chain — operator follow-up required: {}", event.assetId(), left.size(), left);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("unburnt", left);
        if (event.retirementActionId() != null) {
            details.put("retirementCorporateActionId", event.retirementActionId().toString());
        }
        eventPublisher.publishEvent(new AssetRedemptionIncompleteEvent(event.assetId(), details));
    }
}
