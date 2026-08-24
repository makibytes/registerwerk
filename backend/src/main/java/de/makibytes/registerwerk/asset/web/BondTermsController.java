package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.asset.internal.BondTermsService;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Bond terms management for assets with bond-type token standards.
 *
 * <pre>
 *   POST /api/v1/assets/{assetId}/bond-terms — create or update bond terms
 *   GET  /api/v1/assets/{assetId}/bond-terms — retrieve bond terms
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/bond-terms")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class BondTermsController {

    private final BondTermsService bondTermsService;

    public BondTermsController(BondTermsService bondTermsService) {
        this.bondTermsService = bondTermsService;
    }

    @PostMapping
    public ResponseEntity<AssetBondTerms> upsertBondTerms(
            @PathVariable UUID assetId,
            @Valid @RequestBody BondTermsRequest request,
            Authentication auth) {
        return ResponseEntity.ok(bondTermsService.upsert(
                assetId, request, SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    /**
     * Readable by the asset's issuer or its current holders, in addition to REGISTRY_ADMIN —
     * previously this whole controller was REGISTRY_ADMIN-only, so an issuer or investor could
     * never see the terms of the bond they issued or hold. Write (upsert, above) stays operator-
     * only: the class-level {@code @PreAuthorize} still governs it.
     */
    @GetMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN') "
            + "or @assetAccessChecker.canRead(#assetId, authentication) "
            + "or @assetAccessChecker.isHolderOfAsset(#assetId, authentication)")
    public ResponseEntity<AssetBondTerms> getBondTerms(@PathVariable UUID assetId) {
        return ResponseEntity.ok(bondTermsService.get(assetId));
    }
}
