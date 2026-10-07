package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import de.makibytes.registerwerk.externalref.ExternalRefApi;
import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import de.makibytes.registerwerk.asset.internal.AssetLifecycleService;
import de.makibytes.registerwerk.asset.internal.AssetService;
import de.makibytes.registerwerk.kyc.KycApi;
import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.shared.web.DocumentStatusResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycComplianceResponse;
import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.asset.web.dto.*;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.asset.web.AssetMapper;
import de.makibytes.registerwerk.asset.web.DeploymentMapper;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
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
    private final AssetDeploymentService assetDeploymentService;
    private final AuditApi auditApi;
    private final AssetMapper assetMapper;
    private final DeploymentMapper deploymentMapper;
    private final AssetDocumentRepository assetDocumentRepository;
    private final KycApi kycApi;
    private final ExternalRefApi externalRefApi;

    public AssetController(
            AssetService assetService,
            AssetLifecycleService assetLifecycleService,
            AssetDeploymentService assetDeploymentService,
            AuditApi auditApi,
            AssetMapper assetMapper,
            DeploymentMapper deploymentMapper,
            AssetDocumentRepository assetDocumentRepository,
            KycApi kycApi,
            ExternalRefApi externalRefApi) {
        this.assetService = assetService;
        this.assetLifecycleService = assetLifecycleService;
        this.assetDeploymentService = assetDeploymentService;
        this.auditApi = auditApi;
        this.assetMapper = assetMapper;
        this.deploymentMapper = deploymentMapper;
        this.assetDocumentRepository = assetDocumentRepository;
        this.kycApi = kycApi;
        this.externalRefApi = externalRefApi;
    }

    /** Creates a new asset. */
    @PostMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'ISSUER', 'COMPANY_ADMIN')")
    public ResponseEntity<AssetResponse> createAsset(
            @RequestBody @Valid AssetCreateRequest request,
            Authentication auth) {
        Asset asset = assetMapper.toEntity(request);
        asset.setIssuerId(resolveIssuerId(request, auth));
        Asset created = assetService.createAsset(asset, extractActorId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created, auth, false));
    }

    /**
     * Returns a paginated list of assets with optional filters. A non-admin/audit caller
     * is always scoped to their own entity's assets — {@code issuerId} is ignored (forced
     * to the caller's own entity) rather than trusted from the query string, otherwise any
     * authenticated customer could browse every other issuer's assets across the registry.
     * {@code tokenStandard} (invalid value: 400) and {@code search} (name / ISIN / asset number,
     * case-insensitive contains, at most 200 characters) narrow the result for every caller.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AssetResponse>> listAssets(
            @RequestParam(required = false) UUID issuerId,
            @RequestParam(required = false) AssetStatus status,
            @RequestParam(required = false) TokenStandard tokenStandard,
            @RequestParam(required = false) String search,
            Authentication auth,
            Pageable pageable) {
        UUID effectiveIssuerId = SecurityUtils.isAdminOrAudit(auth) ? issuerId : SecurityUtils.extractEntityId(auth);
        Page<Asset> page = assetService.listAssets(effectiveIssuerId, status, tokenStandard, search, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(asset -> toResponse(asset, auth, false))));
    }

    /** Returns a single asset by ID. */
    @GetMapping("/{id}")
    @PreAuthorize("@assetAccessChecker.canRead(#id, authentication)")
    public ResponseEntity<AssetResponse> getAsset(@PathVariable UUID id, Authentication auth) {
        Asset asset = assetService.getAsset(id);
        boolean hasTermSheet = assetDocumentRepository.existsByAssetIdAndDeletedAtIsNull(id);
        return ResponseEntity.ok(toResponse(asset, auth, hasTermSheet));
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
            kycApi.checkCompliance(asset.getIssuerId(), jurisdiction);
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

    /** Updates an asset (full or partial — all fields optional). */
    @RequestMapping(path = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<AssetResponse> updateAsset(
            @PathVariable UUID id,
            @RequestBody @Valid AssetUpdateRequest request,
            Authentication auth) {
        Asset patch = new Asset();
        patch.setName(request.name());
        patch.setIsin(request.isin());
        patch.setPublicData(request.publicData());
        patch.setJurisdiction(request.jurisdiction());
        patch.setChain(request.chain());
        patch.setNetwork(request.network());
        patch.setCurrency(request.currency());
        patch.setIssueSize(request.issueSize());
        patch.setDenomination(request.denomination());
        patch.setIssueDate(request.issueDate());
        patch.setMaturityDate(request.maturityDate());
        patch.setMinInvestmentAmount(request.minInvestmentAmount());
        patch.setMaxHoldingAmount(request.maxHoldingAmount());
        return ResponseEntity.ok(toResponse(assetService.updateAsset(id, patch, extractActorId(auth)), auth, false));
    }

    /**
     * Sets the asset's MiFID II target market (F-BLOCKER-11) — which client categories may
     * subscribe to or acquire it, and the minimum investor knowledge/experience required.
     * REGISTRY_ADMIN or the asset's own issuer; unlike KYC classification (operator/compliance
     * only), the issuer is the one who determines who a product is designed for.
     */
    @PutMapping("/{id}/target-market")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<AssetResponse> updateTargetMarket(
            @PathVariable UUID id,
            @Valid @RequestBody de.makibytes.registerwerk.asset.web.dto.TargetMarketUpdateRequest request,
            Authentication auth) {
        java.util.Set<de.makibytes.registerwerk.customer.api.ClientCategory> categories =
                request.categories() != null ? request.categories() : java.util.Set.of();
        Asset updated = assetService.updateTargetMarket(id, categories, request.minExperience(), extractActorId(auth));
        return ResponseEntity.ok(toResponse(updated, auth, false));
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
            @RequestBody @Valid de.makibytes.registerwerk.asset.web.dto.AssetRejectionRequest body,
            Authentication auth) {
        assetLifecycleService.reject(id, body.reason().trim(), extractActorId(auth));
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

    /** Reactivates a suspended asset (SUSPENDED → ISSUED) — correction path for a wrongful suspend. */
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> reactivateAsset(@PathVariable UUID id, Authentication auth) {
        assetLifecycleService.reactivate(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Redeems an asset (ISSUED/SUSPENDED → REDEEMED), which burns every paid holder on-chain.
     * T3-01: REGISTRY_ADMIN only (the issuer lost this endpoint), step-up + 4-eyes, with a stated
     * legal basis and reference; the service refuses while holders are unpaid (see
     * {@code AssetLifecycleService#redeem}).
     */
    @RequiresIdempotencyKey
    @PostMapping("/{id}/redeem")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ASSET_REDEMPTION")
    public ResponseEntity<Void> redeemAsset(
            @PathVariable UUID id,
            @RequestBody @Valid RedeemRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        assetLifecycleService.redeem(id, request.legalBasis(), request.reference(), extractActorId(auth), approverId);
        return ResponseEntity.noContent().build();
    }

    /** Deploys an asset to a chain (alias for POST /assets/{id}/deployments). */
    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#id, authentication)")
    public ResponseEntity<DeploymentResponse> deployAsset(
            @PathVariable UUID id,
            @RequestBody @Valid DeploymentCreateRequest request,
            Authentication auth) {
        UUID actorId = extractActorId(auth);
        AssetDeployment deployment = assetDeploymentService.deploy(id, request.chain(), request.network(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(deploymentMapper.toResponse(deployment));
    }

    /** Returns the audit log for an asset. */
    @GetMapping("/{id}/audit-log")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#id, authentication)")
    public ResponseEntity<PageResponse<AuditEventView>> getAuditLog(
            @PathVariable UUID id,
            Pageable pageable) {
        return ResponseEntity.ok(PageResponse.of(auditApi.findBySubject("Asset", id, pageable)));
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

    private static java.util.List<String> splitWallets(String csv) {
        return csv == null || csv.isBlank() ? java.util.List.of() : java.util.List.of(csv.split(","));
    }

    private AssetResponse toResponse(Asset asset, Authentication authentication, boolean hasTermSheet) {
        AssetResponse base = assetMapper.toResponse(asset);
        return new AssetResponse(
                base.id(),
                base.assetNumber(),
                base.issuerId(),
                base.name(),
                base.isin(),
                base.tokenStandard(),
                base.chain(),
                base.network(),
                base.onchainLevel(),
                base.status(),
                asset.getJurisdiction(),
                base.createdAt(),
                base.updatedAt(),
                hasTermSheet,
                externalRefApi
                        .findExternalId(authentication, ExternalReferenceSubjectType.ASSET, asset.getId())
                        .orElse(null),
                asset.getCurrency(),
                asset.getIssueSize(),
                asset.getDenomination(),
                asset.getIssueDate(),
                asset.getMaturityDate(),
                asset.getTargetMarketCategories(),
                asset.getTargetMarketMinExperience(),
                asset.getMinInvestmentAmount(),
                asset.getMaxHoldingAmount(),
                asset.getHolderSyncStatus(),
                asset.getHolderSyncBlockedReason(),
                splitWallets(asset.getHolderSyncUnmappedWallets()),
                asset.getLastSuccessfulHolderSyncAt(),
                asset.getHolderSyncOffchainRows()
        );
    }

    /**
     * A non-admin caller can only ever create assets under their own entity — an explicit
     * {@code issuerId} in the request body is trusted only for REGISTRY_ADMIN (who has no
     * entity of their own and legitimately creates assets on behalf of any issuer).
     * Otherwise a customer could set an arbitrary {@code issuerId} and create draft assets
     * attributed to a different company.
     */
    private UUID resolveIssuerId(AssetCreateRequest request, Authentication auth) {
        if (SecurityUtils.isAdminOrAudit(auth) && request.issuerId() != null) {
            return request.issuerId();
        }

        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new IllegalArgumentException(
                    "issuerId is required when no authenticated entity is available");
        }
        return entityId;
    }
}
