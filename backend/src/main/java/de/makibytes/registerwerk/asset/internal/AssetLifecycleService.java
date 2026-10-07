package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetSubmittedEvent;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.asset.events.AssetIssuedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.events.AssetReactivatedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedemptionCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.RegisterUnits;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages asset state transitions through the approval and issuance workflow.
 *
 * <pre>
 *   DRAFT → PENDING_APPROVAL → APPROVED → ISSUED ⇄ SUSPENDED
 *                           ↘ DRAFT (rejected)  ↓      ↓
 *                                   REDEMPTION_PENDING ┘ → REDEEMED (once every burn is final, Wave 0b C7)
 * </pre>
 */
@Service
@Transactional
public class AssetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AssetLifecycleService.class);

    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetHolderRepository holderRepository;
    private final RedemptionReadinessPort redemptionReadiness;
    private final org.springframework.beans.factory.ObjectProvider<de.makibytes.registerwerk.asset.api.RedemptionBlocker> redemptionBlockers;
    private final AssetDeploymentRepository deploymentRepository;

    public AssetLifecycleService(
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            AssetBondTermsRepository bondTermsRepository,
            AssetHolderRepository holderRepository,
            RedemptionReadinessPort redemptionReadiness,
            org.springframework.beans.factory.ObjectProvider<de.makibytes.registerwerk.asset.api.RedemptionBlocker> redemptionBlockers,
            AssetDeploymentRepository deploymentRepository) {
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.bondTermsRepository = bondTermsRepository;
        this.holderRepository = holderRepository;
        this.redemptionReadiness = redemptionReadiness;
        this.redemptionBlockers = redemptionBlockers;
        this.deploymentRepository = deploymentRepository;
    }

    /** Submits a DRAFT asset for approval → PENDING_APPROVAL. */
    @CacheEvict(value = "assets", key = "#assetId")
    public void submit(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.DRAFT);
        asset.setStatus(AssetStatus.PENDING_APPROVAL);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetSubmittedEvent(assetId, actorId, null));
        log.info("Asset submitted for approval: id={}", assetId);
    }

    /** Approves a PENDING_APPROVAL asset → APPROVED. */
    @CacheEvict(value = "assets", key = "#assetId")
    public void approve(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.APPROVED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetApprovedEvent(assetId, actorId, null));
        log.info("Asset approved: id={}", assetId);
    }

    /** Rejects a PENDING_APPROVAL asset → back to DRAFT. */
    @CacheEvict(value = "assets", key = "#assetId")
    public void reject(UUID assetId, String reason, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.DRAFT);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetRejectedEvent(assetId, actorId, null, reason));
        log.info("Asset rejected: id={}", assetId);
    }

    /**
     * Issues an APPROVED asset → ISSUED.
     *
     * <p>This method only owns the DB status transition. On-chain deployment is a separate,
     * explicit operator action — {@code AssetDeploymentService.deploy(...)}, invoked via
     * {@code POST /api/v1/assets/{id}/deploy} ({@code AssetController.deployAsset}) or the
     * equivalent {@code POST /api/v1/assets/{assetId}/deployments} ({@code
     * DeploymentController.deployToChain}) — and is not triggered automatically here.
     */
    @CacheEvict(value = "assets", key = "#assetId")
    public void issue(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.APPROVED);
        asset.setStatus(AssetStatus.ISSUED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetIssuedEvent(assetId, actorId, null));
        log.info("Asset issued: id={}", assetId);
    }

    /** Suspends an ISSUED asset → SUSPENDED. */
    @CacheEvict(value = "assets", key = "#assetId")
    public void suspend(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.ISSUED);
        asset.setStatus(AssetStatus.SUSPENDED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetSuspendedEvent(assetId, actorId, null));
        log.info("Asset suspended: id={}", assetId);
    }

    /**
     * Reactivates a SUSPENDED asset back to ISSUED — the correction path for a wrongful
     * suspend, mirroring customer/org entities' own suspend ↔ reactivate. Without this, a
     * mis-clicked suspend is permanent short of a manual DB fix.
     */
    @CacheEvict(value = "assets", key = "#assetId")
    public void reactivate(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.SUSPENDED);
        asset.setStatus(AssetStatus.ISSUED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetReactivatedEvent(assetId, actorId, null));
        log.info("Asset reactivated: id={}", assetId);
    }

    /**
     * Starts the redemption of an ISSUED or SUSPENDED asset → {@code REDEMPTION_PENDING}. Publishes
     * {@link AssetRedeemedEvent}, which {@link AssetRedemptionListener} uses to dispatch the actual on-chain
     * burn/retire for standards it can automate; this method itself only owns the DB status transition and, for
     * bonds, reconciling {@link BondStatus}.
     *
     * <p>Wave 0b C7: the asset is REDEEMED only once every burn the redemption dispatched is final (final receipt plus
     * the indexed BURN transfer) - see {@link #completeRedemption}. Burns are submitted, not done, when the event is
     * handled; flipping the asset on submission left a REDEEMED asset with live tokens whenever a burn reverted.
     * Calling this again while the asset is REDEMPTION_PENDING resumes it (same audited, step-up + 4-eyes endpoint):
     * burns that failed are re-dispatched, burns already submitted or confirmed never are.
     *
     * <p>C5: refused (409) on a deployment whose token does not count in whole units - the burn amounts are the
     * register's raw base units.
     *
     * <p>T3-01: redemption burns every holder, so it must never run ahead of the payout. The
     * endpoint is REGISTRY_ADMIN + step-up + 4-eyes; this method refuses (409) while
     * <ul>
     *   <li>any corporate action is still in flight,</li>
     *   <li>an active {@link HolderKind#NOMINEE_POOL} entry holds units (pools must be unwound
     *       first — burning a pool contract breaks its internal accounting),</li>
     *   <li>for bonds: no REDEMPTION/CALL action has settled — including a DEFAULTED bond, whose
     *       status is never silently overwritten with REDEEMED.</li>
     * </ul>
     * Non-bond assets have no corporate action to point at; the stated {@code legalBasis}
     * (e.g. eWpG §26 Einziehung) and {@code reference} plus the second approver are the record.
     */
    @CacheEvict(value = "assets", key = "#assetId")
    public void redeem(UUID assetId, String legalBasis, String reference, UUID actorId, UUID dualControlApproverId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        boolean resume = asset.getStatus() == AssetStatus.REDEMPTION_PENDING;
        if (!resume && asset.getStatus() != AssetStatus.ISSUED && asset.getStatus() != AssetStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("Asset",
                asset.getStatus().name(), AssetStatus.REDEEMED.name());
        }
        if (legalBasis == null || legalBasis.isBlank() || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Redemption requires a legal basis and a reference.");
        }
        RegisterUnits.requireWholeUnits(deploymentRepository, assetId, "Asset redemption");
        if (redemptionReadiness.hasOpenCorporateAction(assetId)) {
            throw new IllegalStateException("Asset " + assetId + " has a corporate action in progress — "
                    + "settle or cancel it before redeeming.");
        }
        boolean poolHoldsUnits = holderRepository.findActiveByAssetId(assetId).stream()
                .filter(h -> h.getHolderKind() == HolderKind.NOMINEE_POOL)
                .map(AssetHolder::getNominalAmount)
                .anyMatch(n -> n != null && n.signum() > 0);
        if (poolHoldsUnits) {
            throw new IllegalStateException("Asset " + assetId + " still has units in a nominee pool "
                    + "(lending market, DvP escrow, desk or facility) — unwind the pools before redeeming.");
        }
        for (var blocker : (Iterable<de.makibytes.registerwerk.asset.api.RedemptionBlocker>) redemptionBlockers.orderedStream()::iterator) {
            Optional<String> reason = blocker.blocksRedemption(assetId);
            if (reason.isPresent()) {
                throw new IllegalStateException("Asset " + assetId + " cannot be redeemed: " + reason.get());
            }
        }
        Optional<AssetBondTerms> bondTerms = bondTermsRepository.findById(assetId);
        UUID retirementActionId = null;
        if (bondTerms.isPresent()) {
            Optional<RedemptionReadinessPort.SettledRetirement> retirement =
                    redemptionReadiness.settledRetirementAction(assetId);
            if (retirement.isEmpty()) {
                throw new IllegalStateException(bondTerms.get().getBondStatus() == BondStatus.DEFAULTED
                        ? "Bond " + assetId + " is DEFAULTED and no redemption has been settled — it cannot be "
                                + "marked REDEEMED."
                        : "Bond " + assetId + " has no settled REDEMPTION or CALL corporate action — "
                                + "holders must be paid out before the asset is redeemed.");
            }
            retirementActionId = retirement.get().corporateActionId();
        }
        asset.setStatus(AssetStatus.REDEMPTION_PENDING);
        assetRepository.save(asset);

        // Asset.status and AssetBondTerms.bondStatus were two independent fields with no
        // reconciliation between them — a bond could show Asset.REDEEMED while its own
        // BondStatus stayed ACTIVE (or vice-versa via CantonBondOperations.redeem). Keep them
        // in sync from whichever side redeems first.
        bondTerms.ifPresent(terms -> {
            // A called bond stays CALLED (T3-05: the settled CALL already retired it).
            if (terms.getBondStatus() != BondStatus.REDEEMED && terms.getBondStatus() != BondStatus.CALLED) {
                terms.setBondStatus(BondStatus.REDEEMED);
                bondTermsRepository.save(terms);
            }
        });

        eventPublisher.publishEvent(new AssetRedeemedEvent(assetId, actorId, "REGISTRY_ADMIN",
                legalBasis.trim(), reference.trim(), dualControlApproverId, retirementActionId));
        log.info("Asset redemption {}: id={} (REDEMPTION_PENDING until every burn is final)",
                resume ? "resumed" : "started", assetId);
    }

    /**
     * Wave 0b C7: REDEMPTION_PENDING → REDEEMED, called by the redemption listener / burn finalizer when there is
     * nothing left to burn or every burn is confirmed. Idempotent: anything but REDEMPTION_PENDING is left alone.
     *
     * @return true when this call completed the redemption
     */
    @CacheEvict(value = "assets", key = "#assetId")
    public boolean completeRedemption(UUID assetId, int burns) {
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset == null || asset.getStatus() != AssetStatus.REDEMPTION_PENDING) {
            return false;
        }
        asset.setStatus(AssetStatus.REDEEMED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetRedemptionCompletedEvent(assetId, burns));
        log.info("Asset redeemed: id={} (all {} burn(s) final)", assetId, burns);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Asset getAndRequireStatus(UUID assetId, AssetStatus required) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.getStatus() != required) {
            throw new InvalidStateTransitionException("Asset",
                asset.getStatus().name(), required.name() + " (required current state)");
        }
        return asset;
    }
}
