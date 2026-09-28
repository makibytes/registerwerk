package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.asset.internal.BondTermsService;
import de.makibytes.registerwerk.asset.internal.CouponScheduleService;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.asset.web.dto.CouponScheduleEntryResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Bond terms management for assets with bond-type token standards.
 *
 * <pre>
 *   POST /api/v1/assets/{assetId}/bond-terms          — create or update bond terms (until issuance)
 *   GET  /api/v1/assets/{assetId}/bond-terms          — retrieve bond terms
 *   GET  /api/v1/assets/{assetId}/bond-terms/schedule — generated coupon schedule
 * </pre>
 *
 * <p>After issuance the POST answers 409; economic terms then change only via
 * {@code POST /api/v1/assets/{assetId}/terms-amendments} (step-up + second approver).
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/bond-terms")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class BondTermsController {

    private final BondTermsService bondTermsService;
    private final CouponScheduleService couponScheduleService;

    public BondTermsController(BondTermsService bondTermsService, CouponScheduleService couponScheduleService) {
        this.bondTermsService = bondTermsService;
        this.couponScheduleService = couponScheduleService;
    }

    @PostMapping
    @RequiresStepUp(reason = "BOND_TERMS_UPSERT")
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

    /** Same readers as the terms themselves. */
    @GetMapping("/schedule")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') "
            + "or @assetAccessChecker.canRead(#assetId, authentication) "
            + "or @assetAccessChecker.isHolderOfAsset(#assetId, authentication)")
    public ResponseEntity<List<CouponScheduleEntryResponse>> getCouponSchedule(@PathVariable UUID assetId) {
        return ResponseEntity.ok(couponScheduleService.schedule(assetId).stream()
                .map(CouponScheduleEntryResponse::from).toList());
    }
}
