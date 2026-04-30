package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.asset.AssetLifecycleService;
import de.makibytes.registerwerk.application.asset.AssetService;
import de.makibytes.registerwerk.application.kyc.KycComplianceService;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDocumentRepository;
import de.makibytes.registerwerk.web.dto.DocumentStatusResponse;
import de.makibytes.registerwerk.web.dto.KycComplianceResponse;
import de.makibytes.registerwerk.domain.audit.AuditEvent;
import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AuditEventRepository;
import de.makibytes.registerwerk.web.dto.*;
import de.makibytes.registerwerk.web.mapper.AssetMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for asset CRUD and lifecycle state machine operations.
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final AssetService assetService;
    private final AssetLifecycleService assetLifecycleService;
    private final AuditEventRepository auditEventRepository;
    private final AssetMapper assetMapper;
    private final AssetDocumentRepository assetDocumentRepository;
    private final KycComplianceService kycComplianceService;

    public AssetController(
            AssetService assetService,
            AssetLifecycleService assetLifecycleService,
            AuditEventRepository auditEventRepository,
            AssetMapper assetMapper,
            AssetDocumentRepository assetDocumentRepository,
            KycComplianceService kycComplianceService) {
        this.assetService = assetService;
        this.assetLifecycleService = assetLifecycleService;
        this.auditEventRepository = auditEventRepository;
        this.assetMapper = assetMapper;
        this.assetDocumentRepository = assetDocumentRepository;
        this.kycComplianceService = kycComplianceService;
    }

    /** Creates a new asset. */
    @PostMapping
    public ResponseEntity<AssetResponse> createAsset(
            @RequestBody @Valid AssetCreateRequest request,
            Authentication auth) {
        Asset asset = assetMapper.toEntity(request);
        Asset created = assetService.createAsset(asset, extractActorId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(assetMapper.toResponse(created));
    }

    /** Returns a paginated list of assets with optional filters. */
    @GetMapping
    public ResponseEntity<PageResponse<AssetResponse>> listAssets(
            @RequestParam(required = false) UUID issuerId,
            @RequestParam(required = false) AssetStatus status,
            Pageable pageable) {
        Page<Asset> page = assetService.listAssets(issuerId, status, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(assetMapper::toResponse)));
    }

    /** Returns a single asset by ID. */
    @GetMapping("/{id}")
    @PreAuthorize("@assetAccessChecker.canRead(#id, authentication)")
    public ResponseEntity<AssetResponse> getAsset(@PathVariable UUID id) {
        Asset asset = assetService.getAsset(id);
        AssetResponse base = assetMapper.toResponse(asset);
        boolean hasTermSheet = assetDocumentRepository.existsByAssetIdAndDeletedAtIsNull(id);
        return ResponseEntity.ok(new AssetResponse(base.id(), base.assetNumber(), base.issuerId(),
            base.name(), base.isin(), base.tokenStandard(), base.onchainLevel(), base.status(),
            asset.getJurisdiction(), base.createdAt(), hasTermSheet));
    }

    /**
     * Returns the KYC compliance status for the asset's issuer against the asset's jurisdiction.
     * Used by operators before approving an asset to check if all required documents are present.
     */
    @GetMapping("/{id}/kyc-compliance")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#id, authentication)")
    public ResponseEntity<KycComplianceResponse> getKycCompliance(@PathVariable UUID id) {
        Asset asset = assetService.getAsset(id);
        Jurisdiction jurisdiction = asset.getJurisdiction();
        if (jurisdiction == null) {
            return ResponseEntity.badRequest().build();
        }
        KycComplianceService.ComplianceResult result =
            kycComplianceService.checkCompliance(asset.getIssuerId(), jurisdiction);
        return ResponseEntity.ok(toComplianceResponse(result));
    }

    private KycComplianceResponse toComplianceResponse(KycComplianceService.ComplianceResult r) {
        var docs = r.documents().stream().map(d -> new DocumentStatusResponse(
            d.documentType().name(), d.mandatory(), d.localName(), d.description(),
            d.present(), d.expired(), d.tooOld(), d.documentDate(), d.documentId()
        )).toList();
        return new KycComplianceResponse(
            r.jurisdiction().name(), r.jurisdiction().displayName,
            r.entityId(), docs, r.fullyCompliant(), r.missingCount(), r.expiredCount(), r.tooOldCount()
        );
    }

    /** Partially updates an asset. */
    @PatchMapping("/{id}")
    public ResponseEntity<AssetResponse> updateAsset(
            @PathVariable UUID id,
            @RequestBody @Valid AssetUpdateRequest request) {
        Asset patch = new Asset();
        patch.setName(request.name());
        patch.setIsin(request.isin());
        patch.setPublicData(request.publicData());
        patch.setJurisdiction(request.jurisdiction());
        return ResponseEntity.ok(assetMapper.toResponse(assetService.updateAsset(id, patch)));
    }

    /** Submits an asset for approval (DRAFT → PENDING_APPROVAL). */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<Void> submitForApproval(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.submit(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Approves an asset (PENDING_APPROVAL → APPROVED). */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> approveAsset(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.approve(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Rejects an asset (PENDING_APPROVAL → DRAFT). */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> rejectAsset(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        assetLifecycleService.reject(id, body.getOrDefault("reason", ""), extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Issues an asset (APPROVED → ISSUED). */
    @PostMapping("/{id}/issue")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<Void> issueAsset(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.issue(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Suspends an issued asset (ISSUED → SUSPENDED). */
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> suspendAsset(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.suspend(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Redeems an asset (ISSUED/SUSPENDED → REDEEMED). */
    @PostMapping("/{id}/redeem")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<Void> redeemAsset(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.redeem(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** Returns the audit log for an asset. */
    @GetMapping("/{id}/audit-log")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#id, authentication)")
    public ResponseEntity<PageResponse<AuditEvent>> getAuditLog(
            @PathVariable UUID id,
            Pageable pageable) {
        Page<AuditEvent> events = auditEventRepository.findBySubjectTypeAndSubjectId("Asset", id, pageable);
        return ResponseEntity.ok(PageResponse.of(events));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
