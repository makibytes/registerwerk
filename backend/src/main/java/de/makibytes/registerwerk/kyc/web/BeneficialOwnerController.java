package de.makibytes.registerwerk.kyc.web;

import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.internal.BeneficialOwnerService;
import de.makibytes.registerwerk.kyc.api.EddApproval;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
import de.makibytes.registerwerk.kyc.web.dto.CeaseBeneficialOwnerRequest;
import de.makibytes.registerwerk.kyc.web.dto.EddApprovalRequest;
import de.makibytes.registerwerk.kyc.web.dto.EddApprovalResponse;
import de.makibytes.registerwerk.kyc.web.dto.OwnershipSummaryResponse;
import de.makibytes.registerwerk.kyc.web.dto.VerifyBeneficialOwnerRequest;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for beneficial-owner (UBO) registration — GwG §3, AMLR Art. 42.
 * Adding a beneficial owner immediately triggers a sanctions/PEP screening for them
 * (see {@link BeneficialOwnerService#addBeneficialOwner}).
 */
@RestController
@RequestMapping("/api/v1/entities/{entityId}/beneficial-owners")
public class BeneficialOwnerController {

    private final BeneficialOwnerService beneficialOwnerService;

    public BeneficialOwnerController(BeneficialOwnerService beneficialOwnerService) {
        this.beneficialOwnerService = beneficialOwnerService;
    }

    /** The UBO list holds personal data: operator compliance roles and the entity's own COMPANY_ADMIN only (6-07). */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT') or "
            + "(hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner(#entityId, authentication))")
    public ResponseEntity<List<BeneficialOwnerResponse>> list(@PathVariable UUID entityId) {
        List<BeneficialOwnerResponse> result = beneficialOwnerService.listActive(entityId).stream()
                .map(bo -> BeneficialOwnerResponse.from(bo, beneficialOwnerService.requireNaturalPerson(bo.getNaturalPersonId())))
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<BeneficialOwnerResponse> add(
            @PathVariable UUID entityId,
            @Valid @RequestBody BeneficialOwnerRequest request,
            Authentication auth) {
        BeneficialOwner saved = beneficialOwnerService.addBeneficialOwner(
                entityId, request.person(), request.ownershipPct(), request.controlType(), request.source(), request.fallbackReason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BeneficialOwnerResponse.from(saved, beneficialOwnerService.requireNaturalPerson(saved.getNaturalPersonId())));
    }

    /** Identified ownership, unexplained share and fallback state of the entity (6-17). */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT') or "
            + "(hasRole('COMPANY_ADMIN') and @entityOwnershipChecker.isOwner(#entityId, authentication))")
    public ResponseEntity<OwnershipSummaryResponse> summary(@PathVariable UUID entityId) {
        return ResponseEntity.ok(OwnershipSummaryResponse.from(beneficialOwnerService.ownershipSummary(entityId)));
    }

    /**
     * Ceases a beneficial owner. Step-up and a second approver; mandatory reason; refused while the person's
     * screening is unresolved (6-16). A DELETE with a JSON body.
     */
    @DeleteMapping("/{beneficialOwnerId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(requireSecondApprover = true, reason = "BENEFICIAL_OWNER_CEASE")
    public ResponseEntity<BeneficialOwnerResponse> cease(
            @PathVariable UUID entityId,
            @PathVariable UUID beneficialOwnerId,
            @RequestBody @Valid CeaseBeneficialOwnerRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        BeneficialOwner saved = beneficialOwnerService.ceaseBeneficialOwner(
                entityId, beneficialOwnerId, request.reason(), request.documentId(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.ok(BeneficialOwnerResponse.from(saved, beneficialOwnerService.requireNaturalPerson(saved.getNaturalPersonId())));
    }

    /** Verifies the owner against a stored KYC document (extract id). */
    @PostMapping("/{beneficialOwnerId}/verify")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<BeneficialOwnerResponse> verify(
            @PathVariable UUID entityId,
            @PathVariable UUID beneficialOwnerId,
            @RequestBody @Valid VerifyBeneficialOwnerRequest request,
            Authentication auth) {
        BeneficialOwner saved = beneficialOwnerService.verifyBeneficialOwner(
                entityId, beneficialOwnerId, request.documentId(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(BeneficialOwnerResponse.from(saved, beneficialOwnerService.requireNaturalPerson(saved.getNaturalPersonId())));
    }

    /** EDD approval of a confirmed-PEP beneficial owner: REGISTRY_ADMIN, step-up and a second approver (6-17). */
    @PostMapping("/{beneficialOwnerId}/edd-approvals")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "PEP_EDD_APPROVE")
    public ResponseEntity<EddApprovalResponse> approveEdd(
            @PathVariable UUID entityId,
            @PathVariable UUID beneficialOwnerId,
            @RequestBody @Valid EddApprovalRequest request,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            Authentication auth) {
        EddApproval saved = beneficialOwnerService.approveEdd(
                entityId, beneficialOwnerId, request.note(), request.reviewDueDate(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.status(HttpStatus.CREATED).body(EddApprovalResponse.from(saved));
    }
}
