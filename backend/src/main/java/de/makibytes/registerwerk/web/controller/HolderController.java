package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.asset.HolderService;
import de.makibytes.registerwerk.application.asset.LiveHolderService;
import de.makibytes.registerwerk.application.blockchain.WhitelistService;
import de.makibytes.registerwerk.application.customer.CompanyExternalReferenceService;
import de.makibytes.registerwerk.application.indexer.HolderDataService;
import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.web.dto.HolderCreateRequest;
import de.makibytes.registerwerk.web.dto.HolderResponse;
import de.makibytes.registerwerk.web.dto.LiveHolderResponse;
import de.makibytes.registerwerk.web.dto.PageResponse;
import de.makibytes.registerwerk.web.mapper.HolderMapper;
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
import java.util.Map;
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
    private final LiveHolderService liveHolderService;
    private final WhitelistService whitelistService;
    private final HolderDataService holderDataService;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final HolderMapper holderMapper;
    private final CompanyExternalReferenceService companyExternalReferenceService;

    public HolderController(
            HolderService holderService,
            LiveHolderService liveHolderService,
            WhitelistService whitelistService,
            HolderDataService holderDataService,
            AssetDeploymentRepository assetDeploymentRepository,
            HolderMapper holderMapper,
            CompanyExternalReferenceService companyExternalReferenceService) {
        this.holderService = holderService;
        this.liveHolderService = liveHolderService;
        this.whitelistService = whitelistService;
        this.holderDataService = holderDataService;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.holderMapper = holderMapper;
        this.companyExternalReferenceService = companyExternalReferenceService;
    }

    /**
     * Adds a new holder to an asset.
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<HolderResponse> addHolder(
            @PathVariable UUID assetId,
            Authentication authentication,
            @RequestBody @Valid HolderCreateRequest request) {
        AssetHolder holder = holderService.addHolder(
            assetId,
            request.investorId(),
            request.walletAddress(),
            request.nominalAmount()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(holder, authentication));
    }

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
     * Removes a holder record.
     */
    @DeleteMapping("/{holderId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> removeHolder(
            @PathVariable UUID assetId,
            @PathVariable UUID holderId,
            Authentication auth) {
        UUID actorId = extractActorId(auth);
        holderService.removeHolder(holderId, actorId);
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
            @RequestBody Map<String, String> body) {
        UUID deploymentId = UUID.fromString(body.get("deploymentId"));
        AssetHolder holder = holderService.getHolder(holderId);
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
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<List<LiveHolderResponse>> getLiveHolders(
            @PathVariable UUID assetId,
            @PathVariable UUID depId) {
        List<LiveHolderResponse> liveHolders = liveHolderService.refreshLiveHolders(depId);
        return ResponseEntity.ok(liveHolders);
    }

    /**
     * Manually refresh holder data from blockchain for a specific asset.
     * Only the asset owner (issuer) or registry admin can trigger this.
     *
     * <p>Path: POST /api/v1/assets/{assetId}/holders/refresh
     */
    @PostMapping("/refresh")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<Map<String, Object>> refreshAssetHolders(
            @PathVariable UUID assetId,
            Authentication auth) {
        try {
            holderDataService.manualRefreshIssuance(assetId.toString());
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Holder data refresh initiated for asset " + assetId
            ));
        } catch (Exception e) {
            log.error("Failed to refresh holders for asset {}: {}", assetId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", "Failed to refresh holder data: " + e.getMessage()
            ));
        }
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
                        .orElse(null)
        );
    }
}
