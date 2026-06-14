package de.makibytes.registerwerk.registertransfer.web;

import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequest;
import de.makibytes.registerwerk.registertransfer.internal.RegisterInspectionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * §10 eWpG register inspection endpoints. Submitting a request is open to
 * authenticated participants; decisions and fulfilment are operator actions.
 */
@RestController
@RequestMapping("/api/v1/register-inspections")
public class RegisterInspectionController {

    private final RegisterInspectionService inspectionService;

    RegisterInspectionController(RegisterInspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RegisterInspectionRequest> submit(
            @RequestBody @Valid RegisterTransferDtos.InspectionSubmitRequest request) {
        RegisterInspectionRequest created = inspectionService.submit(
                request.assetId(), request.requesterEntityId(), request.requesterName(),
                request.requesterEmail(), request.legalBasis(), request.statedInterest());
        return ResponseEntity.status(201).body(created);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
    public RegisterInspectionRequest approve(
            @PathVariable UUID requestId,
            @RequestBody @Valid RegisterTransferDtos.InspectionDecisionRequest request) {
        return inspectionService.approve(requestId, request.operatorEntityId(), request.reason());
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
    public RegisterInspectionRequest reject(
            @PathVariable UUID requestId,
            @RequestBody @Valid RegisterTransferDtos.InspectionDecisionRequest request) {
        return inspectionService.reject(requestId, request.operatorEntityId(), request.reason());
    }

    @PostMapping("/{requestId}/fulfil")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<byte[]> fulfil(@PathVariable UUID requestId) {
        byte[] pdf = inspectionService.fulfil(requestId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Registereinsicht.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/assets/{assetId}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
    public Page<RegisterInspectionRequest> listForAsset(
            @PathVariable UUID assetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inspectionService.listForAsset(assetId, PageRequest.of(page, Math.min(size, 100)));
    }
}
