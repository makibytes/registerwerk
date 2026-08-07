package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionConfirmationService;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.CancelCorporateActionRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.MarkSettledRequest;
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
 * <p>{@code approve-settlement} records the dual-control (Vieraugenprinzip) sign-off that
 * the scheduled settlement job ({@code CorporateActionService.processDailyTransitions})
 * now requires before it will settle a due corporate action — a coupon payment, dividend,
 * split, or redemption moves real money/tokens and must not settle on one actor's say-so.
 *
 * <p>{@code mark-settled} is the manual fallback for every token standard other than Canton
 * bonds: {@code CorporateActionSettlementListener} only ever dispatches settlement
 * automatically for {@code DAML_BOND_*}; every other standard has no other path out of
 * AWAITING_SETTLEMENT.
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

    @PostMapping("/{corporateActionId}/approve-settlement")
    @RequiresStepUp(requireSecondApprover = true, reason = "CORPORATE_ACTION_SETTLEMENT_APPROVAL")
    public ResponseEntity<CorporateAction> approveSettlement(
            @PathVariable UUID corporateActionId, Authentication auth) {
        CorporateAction approved = corporateActionService.approveForSettlement(
                corporateActionId, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(approved);
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
