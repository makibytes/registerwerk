package de.makibytes.registerwerk.kyc.web;

import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.internal.BeneficialOwnerService;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
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
                entityId, request.person(), request.ownershipPct(), request.controlType(), request.source(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BeneficialOwnerResponse.from(saved, beneficialOwnerService.requireNaturalPerson(saved.getNaturalPersonId())));
    }

    @DeleteMapping("/{beneficialOwnerId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<BeneficialOwnerResponse> cease(
            @PathVariable UUID entityId,
            @PathVariable UUID beneficialOwnerId,
            Authentication auth) {
        BeneficialOwner saved = beneficialOwnerService.ceaseBeneficialOwner(
                entityId, beneficialOwnerId, SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(BeneficialOwnerResponse.from(saved, beneficialOwnerService.requireNaturalPerson(saved.getNaturalPersonId())));
    }
}
