package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.api.InvestorLimit;
import de.makibytes.registerwerk.asset.internal.InvestorLimitService;
import de.makibytes.registerwerk.asset.web.dto.InvestorLimitRequest;
import de.makibytes.registerwerk.asset.web.dto.InvestorLimitResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-investor limit overrides on top of an asset's default min-investment/max-holding
 * (F-BLOCKER-12) — e.g. a negotiated cornerstone-investor exception or a lockup on a specific
 * position. REGISTRY_ADMIN or the asset's own issuer, matching {@code AssetController}'s
 * ownership model for issuer-controlled economic terms.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/investor-limits")
public class InvestorLimitController {

    private final InvestorLimitService service;

    public InvestorLimitController(InvestorLimitService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<List<InvestorLimitResponse>> listForAsset(@PathVariable UUID assetId) {
        return ResponseEntity.ok(service.listForAsset(assetId).stream().map(InvestorLimitResponse::from).toList());
    }

    @PutMapping("/{investorEntityId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<InvestorLimitResponse> setLimit(
            @PathVariable UUID assetId,
            @PathVariable UUID investorEntityId,
            @RequestBody InvestorLimitRequest request,
            Authentication auth) {
        InvestorLimit saved = service.setLimit(
                assetId, investorEntityId, request.minInvestmentOverride(),
                request.maxHoldingOverride(), request.lockupUntil(), SecurityUtils.extractUserId(auth));
        return ResponseEntity.ok(InvestorLimitResponse.from(saved));
    }

    @DeleteMapping("/{investorEntityId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<Void> deleteLimit(@PathVariable UUID assetId, @PathVariable UUID investorEntityId) {
        service.deleteLimit(assetId, investorEntityId);
        return ResponseEntity.noContent().build();
    }
}
