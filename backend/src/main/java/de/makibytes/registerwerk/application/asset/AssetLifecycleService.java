package de.makibytes.registerwerk.application.asset;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.application.exception.InvalidStateTransitionException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.domain.enums.OnchainLevel;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
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
    private final AuditEventPublisher auditEventPublisher;
    private final AssetDeploymentService assetDeploymentService;

    public AssetLifecycleService(
            AssetRepository assetRepository,
            AuditEventPublisher auditEventPublisher,
            AssetDeploymentService assetDeploymentService) {
        this.assetRepository = assetRepository;
        this.auditEventPublisher = auditEventPublisher;
        this.assetDeploymentService = assetDeploymentService;
    }

    /** Submits a DRAFT asset for approval → PENDING_APPROVAL. */
    public void submit(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.DRAFT);
        asset.setStatus(AssetStatus.PENDING_APPROVAL);
        assetRepository.save(asset);
        auditEventPublisher.publish("ASSET_SUBMITTED", "Asset", assetId, actorId, null, null);
        log.info("Asset submitted for approval: id={}", assetId);
    }

    /** Approves a PENDING_APPROVAL asset → APPROVED. */
    public void approve(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.APPROVED);
        assetRepository.save(asset);
        auditEventPublisher.publish("ASSET_APPROVED", "Asset", assetId, actorId, null, null);
        log.info("Asset approved: id={}", assetId);
    }

    /** Rejects a PENDING_APPROVAL asset → back to DRAFT. */
    public void reject(UUID assetId, String reason, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.PENDING_APPROVAL);
        asset.setStatus(AssetStatus.DRAFT);
        assetRepository.save(asset);
        auditEventPublisher.publish("ASSET_REJECTED", "Asset", assetId, actorId, null,
            Map.of("reason", reason != null ? reason : ""));
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
        auditEventPublisher.publish("ASSET_ISSUED", "Asset", assetId, actorId, null, null);
        log.info("Asset issued: id={}", assetId);
    }

    /** Suspends an ISSUED asset → SUSPENDED. */
    public void suspend(UUID assetId, UUID actorId) {
        Asset asset = getAndRequireStatus(assetId, AssetStatus.ISSUED);
        asset.setStatus(AssetStatus.SUSPENDED);
        assetRepository.save(asset);
        auditEventPublisher.publish("ASSET_SUSPENDED", "Asset", assetId, actorId, null, null);
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
        auditEventPublisher.publish("ASSET_REDEEMED", "Asset", assetId, actorId, null, null);
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
