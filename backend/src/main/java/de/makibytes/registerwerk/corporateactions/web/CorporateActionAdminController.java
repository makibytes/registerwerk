package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionConfirmationService;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.CancelCorporateActionRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView;
import de.makibytes.registerwerk.corporateactions.web.dto.MarkSettledRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.OverrideAttestationRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.RejectProposalRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operator endpoints for the corporate-action lifecycle.
 * Base path: {@code /api/v1/corporate-actions}
 *
 * <p>{@code confirm-settlement} records the operator's half of the two-party settlement control
 * (see {@code CorporateActionService}'s class javadoc) — the issuer must have already attested
 * (or an operator must have overridden that requirement) before this succeeds.
 *
 * <p>{@code approve-proposal}/{@code reject-proposal} review an issuer's PROPOSED DIVIDEND/
 * SPLIT/CALL before it joins the register-affecting ANNOUNCED pipeline — see
 * {@code IssuerCorporateActionController} for where issuers submit those.
 *
 * <p>{@code mark-settled} is the manual fallback for every settlement path with no automated
 * on-chain adapter: {@code CorporateActionSettlementListener} only ever dispatches settlement
 * automatically for {@code DAML_BOND_*} coupons/redemptions/early-calls; SPLIT has no on-chain
 * primitive on any supported standard and always settles this way.
 */
@RestController
@RequestMapping("/api/v1/corporate-actions")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class CorporateActionAdminController {

    private final CorporateActionService corporateActionService;
    private final CorporateActionConfirmationService confirmationService;

    public CorporateActionAdminController(CorporateActionService corporateActionService,
                                           CorporateActionConfirmationService confirmationService) {
        this.corporateActionService = corporateActionService;
        this.confirmationService = confirmationService;
    }

    @GetMapping
    public ResponseEntity<List<CorporateAction>> listForAsset(@RequestParam UUID assetId) {
        return ResponseEntity.ok(corporateActionService.findByAsset(assetId));
    }

    /** Every issuer-submitted proposal awaiting review, across all assets, oldest first. */
    @GetMapping("/proposals")
    public ResponseEntity<List<CorporateActionView>> pendingProposals() {
        return ResponseEntity.ok(corporateActionService.findProposalsPendingReview().stream()
                .map(CorporateActionView::of).toList());
    }

    @PostMapping("/{corporateActionId}/approve-proposal")
    @RequiresStepUp(requireSecondApprover = false, reason = "CORPORATE_ACTION_PROPOSAL_REVIEW")
    public ResponseEntity<CorporateAction> approveProposal(
            @PathVariable UUID corporateActionId, Authentication auth) {
        return ResponseEntity.ok(corporateActionService.approveProposal(corporateActionId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{corporateActionId}/reject-proposal")
    @RequiresStepUp(requireSecondApprover = false, reason = "CORPORATE_ACTION_PROPOSAL_REVIEW")
    public ResponseEntity<CorporateAction> rejectProposal(
            @PathVariable UUID corporateActionId, @Valid @RequestBody RejectProposalRequest request, Authentication auth) {
        return ResponseEntity.ok(corporateActionService.rejectProposal(corporateActionId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{corporateActionId}/confirm-settlement")
    @RequiresStepUp(requireSecondApprover = false, reason = "CORPORATE_ACTION_SETTLEMENT_CONFIRMATION")
    public ResponseEntity<CorporateAction> confirmSettlement(
            @PathVariable UUID corporateActionId, Authentication auth) {
        CorporateAction confirmed = corporateActionService.confirmSettlementAsOperator(
                corporateActionId, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(confirmed);
    }

    @PostMapping("/{corporateActionId}/override-attestation")
    @RequiresStepUp(requireSecondApprover = false, reason = "CORPORATE_ACTION_ATTESTATION_OVERRIDE")
    public ResponseEntity<CorporateAction> overrideAttestation(
            @PathVariable UUID corporateActionId, @Valid @RequestBody OverrideAttestationRequest request, Authentication auth) {
        return ResponseEntity.ok(corporateActionService.overrideIssuerAttestation(corporateActionId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{corporateActionId}/mark-settled")
    @RequiresStepUp(requireSecondApprover = true, reason = "CORPORATE_ACTION_MANUAL_SETTLEMENT")
    public ResponseEntity<CorporateAction> markSettled(
            @PathVariable UUID corporateActionId, @Valid @RequestBody MarkSettledRequest request, Authentication auth) {
        return ResponseEntity.ok(corporateActionService.markSettledManually(corporateActionId, request.reference(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/{corporateActionId}/cancel")
    @RequiresStepUp(requireSecondApprover = true, reason = "CORPORATE_ACTION_CANCELLATION")
    public ResponseEntity<CorporateAction> cancel(
            @PathVariable UUID corporateActionId, @Valid @RequestBody CancelCorporateActionRequest request, Authentication auth) {
        return ResponseEntity.ok(corporateActionService.cancel(corporateActionId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @GetMapping("/{corporateActionId}/confirmation")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<byte[]> confirmation(@PathVariable UUID corporateActionId) {
        byte[] pdf = confirmationService.generateForOperator(corporateActionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("confirmation-" + corporateActionId + ".pdf").build().toString())
                .body(pdf);
    }

    @GetMapping("/{corporateActionId}/confirmation/iso20022")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT')")
    public ResponseEntity<byte[]> iso20022Confirmation(@PathVariable UUID corporateActionId) {
        byte[] xml = confirmationService.generateIso20022ForOperator(corporateActionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("confirmation-" + corporateActionId + ".xml").build().toString())
                .body(xml);
    }

}
