package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.asset.HolderService;
import de.makibytes.registerwerk.application.blockchain.WhitelistService;
import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.web.dto.HolderCreateRequest;
import de.makibytes.registerwerk.web.dto.HolderResponse;
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
public class HolderController {

    private static final Logger log = LoggerFactory.getLogger(HolderController.class);

    private final HolderService holderService;
    private final WhitelistService whitelistService;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final HolderMapper holderMapper;

    public HolderController(
            HolderService holderService,
            WhitelistService whitelistService,
            AssetDeploymentRepository assetDeploymentRepository,
            HolderMapper holderMapper) {
        this.holderService = holderService;
        this.whitelistService = whitelistService;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.holderMapper = holderMapper;
    }

    /**
     * Adds a new holder to an asset.
     */
    @PostMapping
    public ResponseEntity<HolderResponse> addHolder(
            @PathVariable UUID assetId,
            @RequestBody @Valid HolderCreateRequest request) {
        AssetHolder holder = holderService.addHolder(
            assetId,
            request.investorId(),
            request.walletAddress(),
            request.nominalAmount()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(holderMapper.toResponse(holder));
    }

    /**
     * Returns a paginated list of holders for an asset.
     */
    @GetMapping
    public ResponseEntity<PageResponse<HolderResponse>> listHolders(
            @PathVariable UUID assetId,
            Pageable pageable) {
        Page<AssetHolder> page = holderService.listHolders(assetId, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(holderMapper::toResponse)));
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
    public ResponseEntity<Void> whitelistHolder(
            @PathVariable UUID assetId,
            @PathVariable UUID holderId,
            @RequestBody Map<String, String> body) {
        UUID deploymentId = UUID.fromString(body.get("deploymentId"));
        AssetHolder holder = holderService.getHolder(holderId);
        whitelistService.whitelist(deploymentId, holder.getWalletAddress());
        return ResponseEntity.noContent().build();
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
