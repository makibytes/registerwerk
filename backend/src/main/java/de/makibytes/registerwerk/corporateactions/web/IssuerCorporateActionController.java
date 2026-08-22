package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView;
import de.makibytes.registerwerk.corporateactions.web.dto.IssuerAttestationRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Issuer self-service corporate-action endpoints — an issuer had no capability at all in this
 * module before this: {@code CorporateActionAdminController} is REGISTRY_ADMIN-only, and
 * {@code AssetAccessChecker.canActAsIssuer} (already used elsewhere for exactly this purpose) was
 * never referenced here.
 *
 * <p>A separate controller rather than widening {@code CorporateActionAdminController}'s
 * endpoints: that class carries a class-level {@code hasRole('REGISTRY_ADMIN')}, and mixing
 * surfaces there is exactly how its existing {@code /confirmation} endpoints' role-widening
 * became easy to misread as "REGISTRY_ADMIN-only" at a glance.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/corporate-actions}
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/corporate-actions")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
public class IssuerCorporateActionController {

    private final CorporateActionService corporateActionService;

    public IssuerCorporateActionController(CorporateActionService corporateActionService) {
        this.corporateActionService = corporateActionService;
    }

    @GetMapping
    public ResponseEntity<List<CorporateActionView>> listForAsset(@PathVariable UUID assetId) {
        return ResponseEntity.ok(corporateActionService.findByAsset(assetId).stream()
                .map(CorporateActionView::of).toList());
    }

    /** Proposes a new DIVIDEND/SPLIT/CALL corporate action — starts {@code PROPOSED}, invisible
     *  to the register until an operator reviews it via {@code CorporateActionAdminController}. */
    @PostMapping
    public ResponseEntity<CorporateActionView> propose(
            @PathVariable UUID assetId, @Valid @RequestBody ProposeCorporateActionRequest request, Authentication auth) {
        CorporateAction proposed = corporateActionService.propose(assetId, request,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "ISSUER"));
        return ResponseEntity.ok(CorporateActionView.of(proposed));
    }

    /** Withdraws the issuer's own still-{@code PROPOSED} action. */
    @PostMapping("/{corporateActionId}/withdraw")
    public ResponseEntity<CorporateActionView> withdraw(
            @PathVariable UUID assetId, @PathVariable UUID corporateActionId, Authentication auth) {
        CorporateAction withdrawn = corporateActionService.withdrawProposal(
                assetId, corporateActionId, SecurityUtils.extractUserId(auth));
        return ResponseEntity.ok(CorporateActionView.of(withdrawn));
    }

    /**
     * The issuer's attestation that the underlying obligation/cash-leg for this action's
     * settlement is ready — the first of the two required parties before an operator can confirm
     * settlement. Deliberately not step-up-gated (see {@code IssuerAttestationRequest}'s javadoc).
     */
    @PostMapping("/{corporateActionId}/attest-settlement")
    public ResponseEntity<CorporateActionView> attestSettlement(
            @PathVariable UUID assetId, @PathVariable UUID corporateActionId,
            @Valid @RequestBody IssuerAttestationRequest request, Authentication auth) {
        CorporateAction attested = corporateActionService.attestSettlementAsIssuer(assetId, corporateActionId,
                request.attestationReference(), SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "ISSUER"));
        return ResponseEntity.ok(CorporateActionView.of(attested));
    }
}
