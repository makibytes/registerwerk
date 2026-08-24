package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetSubmittedEvent;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.asset.events.AssetIssuedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.events.AssetReactivatedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages asset state transitions through the approval and issuance workflow.
 *
 * <pre>
 *   DRAFT → PENDING_APPROVAL → APPROVED → ISSUED ⇄ SUSPENDED
 *                           ↘ DRAFT (rejected)  ↓      ↓
 *                                             REDEEMED ┘
 * </pre>
 */
@Service
@Transactional
public class AssetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AssetLifecycleService.class);

    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AssetBondTermsRepository bondTermsRepository;

    public AssetLifecycleService(
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            AssetBondTermsRepository bondTermsRepository) {
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.bondTermsRepository = bondTermsRepository;
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
     * Redeems an ISSUED or SUSPENDED asset → REDEEMED. Publishes {@link AssetRedeemedEvent},
     * which {@link AssetRedemptionListener} uses to dispatch the actual on-chain burn/retire for
     * standards it can automate — this method itself only owns the DB status transition and,
     * for bonds, reconciling {@link BondStatus}.
     */
    @CacheEvict(value = "assets", key = "#assetId")
    public void redeem(UUID assetId, UUID actorId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.getStatus() != AssetStatus.ISSUED && asset.getStatus() != AssetStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("Asset",
                asset.getStatus().name(), AssetStatus.REDEEMED.name());
        }
        asset.setStatus(AssetStatus.REDEEMED);
        assetRepository.save(asset);

        // Asset.status and AssetBondTerms.bondStatus were two independent fields with no
        // reconciliation between them — a bond could show Asset.REDEEMED while its own
        // BondStatus stayed ACTIVE (or vice-versa via CantonBondOperations.redeem). Keep them
        // in sync from whichever side redeems first.
        bondTermsRepository.findById(assetId).ifPresent(terms -> {
            if (terms.getBondStatus() != BondStatus.REDEEMED) {
                terms.setBondStatus(BondStatus.REDEEMED);
                bondTermsRepository.save(terms);
            }
        });

        eventPublisher.publishEvent(new AssetRedeemedEvent(assetId, actorId, null));
        log.info("Asset redeemed: id={}", assetId);
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
