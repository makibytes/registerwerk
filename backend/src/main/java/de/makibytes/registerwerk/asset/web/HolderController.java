package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.HolderChangeRequest;
import de.makibytes.registerwerk.asset.internal.HolderChangeRequestService;
import de.makibytes.registerwerk.asset.internal.HolderInstruction;
import de.makibytes.registerwerk.asset.internal.HolderService;
import de.makibytes.registerwerk.asset.web.dto.HolderChangeRequestBody;
import de.makibytes.registerwerk.asset.web.dto.HolderChangeRequestResponse;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.constraints.NotBlank;
import de.makibytes.registerwerk.asset.internal.LiveHolderService;
import de.makibytes.registerwerk.blockchain.api.WhitelistService;
import de.makibytes.registerwerk.externalref.ExternalRefApi;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.web.dto.HolderCreateRequest;
import de.makibytes.registerwerk.asset.web.dto.WhitelistHolderRequest;
import de.makibytes.registerwerk.asset.web.dto.HolderResponse;
import de.makibytes.registerwerk.asset.web.dto.LiveHolderResponse;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.asset.web.HolderMapper;
import de.makibytes.registerwerk.shared.SecurityUtils;
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

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing asset holders (investor positions).
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/holders")
@PreAuthorize("isAuthenticated()")
public class HolderController {

    private static final Logger log = LoggerFactory.getLogger(HolderController.class);

    private final HolderService holderService;
    private final HolderChangeRequestService changeRequestService;
    private final LiveHolderService liveHolderService;
    private final WhitelistService whitelistService;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final HolderMapper holderMapper;
    private final ExternalRefApi companyExternalReferenceService;

    public HolderController(
            HolderService holderService,
            HolderChangeRequestService changeRequestService,
            LiveHolderService liveHolderService,
            WhitelistService whitelistService,
            AssetDeploymentRepository assetDeploymentRepository,
            HolderMapper holderMapper,
            ExternalRefApi companyExternalReferenceService) {
        this.holderService = holderService;
        this.changeRequestService = changeRequestService;
        this.liveHolderService = liveHolderService;
        this.whitelistService = whitelistService;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.holderMapper = holderMapper;
        this.companyExternalReferenceService = companyExternalReferenceService;
    }

    /**
     * Adds a new holder to an asset. Operator-only (T3-13): the issuer files a change request instead.
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "REGISTER_ENTRY_CREATE")
    public ResponseEntity<HolderResponse> addHolder(
            @PathVariable UUID assetId,
            Authentication authentication,
            @RequestBody @Valid HolderCreateRequest request) {
        AssetHolder holder = holderService.addHolder(
            assetId,
            request.investorId(),
            request.walletAddress(),
            request.nominalAmount(),
            new HolderInstruction(request.instructingParty(), request.instructionReference()),
            extractActorId(authentication),
            SecurityUtils.primaryRole(authentication, "REGISTRY_ADMIN")
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(holder, authentication));
    }

    /**
     * Adds a holder in single entry (Einzeleintragung, §8/§17 eWpG). Generates a
     * pseudonymous holder reference and records the §17(2) attributes and consumer
     * flag that govern the §19 register-statement obligation.
     */
    @PostMapping("/single-entry")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "REGISTER_ENTRY_CREATE")
    public ResponseEntity<HolderResponse> addSingleEntryHolder(
            @PathVariable UUID assetId,
            Authentication authentication,
            @RequestBody @Valid de.makibytes.registerwerk.asset.web.dto.SingleEntryHolderCreateRequest request) {
        AssetHolder holder = holderService.addSingleEntryHolder(
            assetId,
            request.investorId(),
            request.walletAddress(),
            request.nominalAmount(),
            request.isConsumer(),
            request.thirdPartyRights(),
            request.disposalRestrictions(),
            request.legalCapacityNote(),
            new HolderInstruction(request.instructingParty(), request.instructionReference()),
            extractActorId(authentication),
            SecurityUtils.primaryRole(authentication, "REGISTRY_ADMIN")
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(holder, authentication));
    }

    /**
     * Updates a single-entry holder's §17(2) attributes on instruction of an authorised party
     * (§18(1) eWpG). Operator-only with 4-eyes (T3-13): this can remove third-party rights or
     * disposal restrictions, so an issuer cannot do it and no single operator either.
     */
    @PatchMapping("/{holderId}/single-entry-attributes")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "REGISTER_ENTRY_RIGHTS_CHANGE")
    public ResponseEntity<HolderResponse> updateSingleEntryAttributes(
            @PathVariable UUID assetId,
            @PathVariable UUID holderId,
            Authentication authentication,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            @RequestBody @Valid de.makibytes.registerwerk.asset.web.dto.SingleEntryAttributesUpdateRequest request) {
        AssetHolder holder = holderService.updateSingleEntryAttributes(
            assetId, holderId,
            new HolderService.AttributeChange(request.isConsumer(), request.thirdPartyRights(),
                    request.disposalRestrictions(), request.legalCapacityNote(),
                    request.clearThirdPartyRights(), request.clearDisposalRestrictions()),
            new HolderInstruction(request.instructingParty(), request.instructionReference(), approverId, null),
            extractActorId(authentication),
            SecurityUtils.primaryRole(authentication, "REGISTRY_ADMIN")
        );
        return ResponseEntity.ok(toResponse(holder, authentication));
    }

    // ── Issuer change requests (T3-13) ────────────────────────────────────────

    /** The issuer asks for an entry / §17(2) change; the operator decides. */
    @PostMapping("/change-requests")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<HolderChangeRequestResponse> requestChange(
            @PathVariable UUID assetId,
            Authentication authentication,
            @RequestBody @Valid HolderChangeRequestBody body) {
        HolderChangeRequest r = changeRequestService.request(assetId, body.requestType(), body.holderId(),
                body.payload(), body.instructingParty(), body.instructionReference(),
                extractActorId(authentication), SecurityUtils.primaryRole(authentication, "ISSUER"));
        return ResponseEntity.status(HttpStatus.CREATED).body(HolderChangeRequestResponse.from(r));
    }

    @GetMapping("/change-requests")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<List<HolderChangeRequestResponse>> listChangeRequests(@PathVariable UUID assetId) {
        return ResponseEntity.ok(changeRequestService.list(assetId).stream()
                .map(HolderChangeRequestResponse::from).toList());
    }

    @PostMapping("/change-requests/{requestId}/execute")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "REGISTER_ENTRY_RIGHTS_CHANGE")
    public ResponseEntity<HolderChangeRequestResponse> executeChangeRequest(
            @PathVariable UUID assetId,
            @PathVariable UUID requestId,
            Authentication authentication,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        HolderChangeRequest r = changeRequestService.execute(assetId, requestId, extractActorId(authentication),
                SecurityUtils.primaryRole(authentication, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.ok(HolderChangeRequestResponse.from(r));
    }

    @PostMapping("/change-requests/{requestId}/reject")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<HolderChangeRequestResponse> rejectChangeRequest(
            @PathVariable UUID assetId,
            @PathVariable UUID requestId,
            Authentication authentication,
            @RequestBody @Valid RejectChangeRequestBody body) {
        HolderChangeRequest r = changeRequestService.reject(assetId, requestId, body.reason(),
                extractActorId(authentication), SecurityUtils.primaryRole(authentication, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(HolderChangeRequestResponse.from(r));
    }

    public record RejectChangeRequestBody(@NotBlank String reason) {}

    /**
     * Returns a paginated list of holders for an asset.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<PageResponse<HolderResponse>> listHolders(
            @PathVariable UUID assetId,
            Authentication authentication,
            Pageable pageable) {
        Page<AssetHolder> page = holderService.listHolders(assetId, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(holder -> toResponse(holder, authentication))));
    }

    /**
     * Exports the full holder register as CSV — previously the only way to hand this to a
     * compliance officer or auditor was a database export, since every download in the portal
     * was a per-customer PDF, not a data extract.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<String> exportHolders(@PathVariable UUID assetId, Authentication authentication) {
        List<AssetHolder> holders = holderService.listHolders(assetId, Pageable.unpaged()).getContent();
        List<String> header = List.of(
                "holderId", "assetId", "investorId", "walletAddress", "whitelisted",
                "nominalAmount", "acquisitionDate", "entryType", "holderReference",
                "isConsumer", "thirdPartyRights", "disposalRestrictions", "legalCapacityNote");
        List<List<Object>> rows = holders.stream().map(h -> {
            HolderResponse r = toResponse(h, authentication);
            return List.<Object>of(
                    nullToEmpty(r.id()), nullToEmpty(r.assetId()), nullToEmpty(r.investorId()),
                    nullToEmpty(r.walletAddress()), nullToEmpty(r.whitelisted()),
                    nullToEmpty(r.nominalAmount()), nullToEmpty(r.acquisitionDate()),
                    nullToEmpty(r.entryType()), nullToEmpty(r.holderReference()),
                    nullToEmpty(r.isConsumer()), nullToEmpty(r.thirdPartyRights()),
                    nullToEmpty(r.disposalRestrictions()), nullToEmpty(r.legalCapacityNote()));
        }).toList();
        String csv = de.makibytes.registerwerk.shared.CsvWriter.write(header, rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"holder-register-" + assetId + ".csv\"")
                .body(csv);
    }

    private static Object nullToEmpty(Object v) { return v == null ? "" : v; }

    /**
     * Removes a holder record.
     */
    @DeleteMapping("/{holderId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> removeHolder(
            @PathVariable UUID assetId,
            @PathVariable UUID holderId,
            Authentication auth) {
        UUID actorId = extractActorId(auth);
        holderService.removeHolder(assetId, holderId, actorId, SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Whitelists a holder's wallet address on-chain.
     * Requires a deploymentId in the request body to identify which contract to call.
     */
    @PostMapping("/{holderId}/whitelist")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<Void> whitelistHolder(
            @PathVariable UUID assetId,
            @PathVariable UUID holderId,
            @RequestBody @Valid WhitelistHolderRequest body) {
        UUID deploymentId = body.deploymentId();
        assetDeploymentRepository.findByIdAndAssetId(deploymentId, assetId)
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException(
                        "AssetDeployment", deploymentId));
        AssetHolder holder = holderService.getHolder(assetId, holderId);
        whitelistService.whitelist(deploymentId, holder.getWalletAddress());
        return ResponseEntity.noContent().build();
    }

    /**
     * Retrieves live token holder balances and identity information from on-chain Transfer events.
     *
     * <p>Path: GET /api/v1/assets/{assetId}/deployments/{depId}/holders/live
     *
     * <p>Accessible to: REGISTRY_ADMIN, ISSUER, AUDITOR roles.
     *
     * @param assetId      ID of the asset
     * @param depId        ID of the asset deployment
     * @return list of live holders with balance, identity, and whitelist status
     */
    @GetMapping("/{depId}/live")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication))")
    public ResponseEntity<List<LiveHolderResponse>> getLiveHolders(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        List<LiveHolderResponse> liveHolders = liveHolderService.refreshLiveHolders(depId);
        return ResponseEntity.ok(liveHolders);
    }

    // POST /refresh (manual holder sync) lives in indexer.web.HolderSyncController (T2-18): an
    // asset -> indexer dependency here would close a module cycle with indexer -> lending -> asset.

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private HolderResponse toResponse(AssetHolder holder, Authentication authentication) {
        HolderResponse base = holderMapper.toResponse(holder);
        return new HolderResponse(
                base.id(),
                base.assetId(),
                base.investorId(),
                base.walletAddress(),
                base.whitelisted(),
                base.nominalAmount(),
                base.acquisitionDate(),
                companyExternalReferenceService
                        .findExternalId(authentication, ExternalReferenceSubjectType.ASSET_HOLDER, holder.getId())
                        .orElse(null),
                holder.getEntryType() != null ? holder.getEntryType().name() : null,
                holder.getHolderReference(),
                holder.getIsConsumer(),
                holder.getThirdPartyRights(),
                holder.getDisposalRestrictions(),
                holder.getLegalCapacityNote()
        );
    }
}
