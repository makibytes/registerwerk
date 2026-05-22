package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetSubmittedEvent;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.asset.events.AssetIssuedEvent;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages asset state transitions through the approval and issuance workflow.
 *
 * <pre>
 *   DRAFT → PENDING_APPROVAL → APPROVED → ISSUED → SUSPENDED
 *                           ↘ DRAFT (rejected)     ↓
 *                                              REDEEMED
 * </pre>
 */
@Service
@Transactional
public class AssetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AssetLifecycleService.class);

    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AssetDeploymentService assetDeploymentService;

    public AssetLifecycleService(
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            AssetDeploymentService assetDeploymentService) {
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.assetDeploymentService = assetDeploymentService;
    }

    /** Submits a DRAFT asset for approval → PENDING_APPROVAL. */
    public void submit(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.DRAFT);
        asset.setStatus(AssetStatus.PENDING_APPROVAL);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetSubmittedEvent(assetId, actorId, null));
        log.info("Asset submitted for approval: id={}", assetId);
    }

    /** Approves a PENDING_APPROVAL asset → APPROVED. */
    public void approve(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.APPROVED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetApprovedEvent(assetId, actorId, null));
        log.info("Asset approved: id={}", assetId);
    }

    /** Rejects a PENDING_APPROVAL asset → back to DRAFT. */
    public void reject(UUID assetId, String reason, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.DRAFT);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetRejectedEvent(assetId, actorId, null, reason));
        log.info("Asset rejected: id={}", assetId);
    }

    /**
     * Issues an APPROVED asset → ISSUED.
     * If onchainLevel != NONE, a deployment is triggered via {@link AssetDeploymentService}.
     */
    public void issue(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.APPROVED);
        asset.setStatus(AssetStatus.ISSUED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetIssuedEvent(assetId, actorId, null));
        log.info("Asset issued: id={}", assetId);
    }

    /** Suspends an ISSUED asset → SUSPENDED. */
    public void suspend(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.ISSUED);
        asset.setStatus(AssetStatus.SUSPENDED);
        assetRepository.save(asset);
        eventPublisher.publishEvent(new AssetSuspendedEvent(assetId, actorId, null));
        log.info("Asset suspended: id={}", assetId);
    }

    /** Redeems an ISSUED or SUSPENDED asset → REDEEMED. */
    public void redeem(UUID assetId, UUID actorId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.getStatus() != AssetStatus.ISSUED && asset.getStatus() != AssetStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("Asset",
                asset.getStatus().name(), AssetStatus.REDEEMED.name());
        }
        asset.setStatus(AssetStatus.REDEEMED);
        assetRepository.save(asset);
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
