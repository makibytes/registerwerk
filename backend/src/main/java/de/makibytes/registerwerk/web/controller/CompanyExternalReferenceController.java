package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.CompanyExternalReferenceService;
import de.makibytes.registerwerk.domain.customer.CompanyExternalReference;
import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.web.dto.CompanyExternalReferenceLookupResponse;
import de.makibytes.registerwerk.web.dto.CompanyExternalReferenceRequest;
import de.makibytes.registerwerk.web.dto.CompanyExternalReferenceValueResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/company/external-ids")
@PreAuthorize("isAuthenticated()")
public class CompanyExternalReferenceController {

    private final CompanyExternalReferenceService companyExternalReferenceService;

    public CompanyExternalReferenceController(CompanyExternalReferenceService companyExternalReferenceService) {
        this.companyExternalReferenceService = companyExternalReferenceService;
    }

    @GetMapping
    public ResponseEntity<List<CompanyExternalReferenceLookupResponse>> list(
            Authentication authentication,
            @RequestParam(required = false) ExternalReferenceSubjectType subjectType) {
        return ResponseEntity.ok(companyExternalReferenceService.list(authentication, subjectType));
    }

    @GetMapping("/lookup")
    public ResponseEntity<List<CompanyExternalReferenceLookupResponse>> lookup(
            Authentication authentication,
            @RequestParam @NotBlank String externalId,
            @RequestParam(required = false) ExternalReferenceSubjectType subjectType) {
        return ResponseEntity.ok(companyExternalReferenceService.lookup(authentication, externalId, subjectType));
    }

    @PutMapping("/{subjectType}/{subjectId}")
    public ResponseEntity<CompanyExternalReferenceValueResponse> upsert(
            Authentication authentication,
            @PathVariable ExternalReferenceSubjectType subjectType,
            @PathVariable UUID subjectId,
            @Valid @RequestBody CompanyExternalReferenceRequest request) {
        CompanyExternalReference saved = companyExternalReferenceService.upsert(
                authentication,
                subjectType,
                subjectId,
                request.externalId());
        return ResponseEntity.ok(new CompanyExternalReferenceValueResponse(
                saved.getSubjectType(),
                saved.getSubjectId(),
                saved.getExternalId(),
                saved.getUpdatedAt()));
    }

    @DeleteMapping("/{subjectType}/{subjectId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable ExternalReferenceSubjectType subjectType,
            @PathVariable UUID subjectId) {
        companyExternalReferenceService.delete(authentication, subjectType, subjectId);
        return ResponseEntity.noContent().build();
    }
}
