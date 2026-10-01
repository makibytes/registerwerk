package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import de.makibytes.registerwerk.travelrule.internal.CaspAuthorization;
import de.makibytes.registerwerk.travelrule.internal.CaspRegisterImportService;
import de.makibytes.registerwerk.travelrule.internal.CaspRegistryService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Compliance-officer maintenance of the counterparty CASP authorization
 * register (MiCA Reg (EU) 2023/1114). Entries mirror the ESMA / NCA registers
 * and drive the Travel Rule counterparty check: after 1 July 2026, outbound
 * transfers to counterparties without MiCA authorization are rejected.
 */
@RestController
@RequestMapping("/api/v1/compliance/casp-register")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
@Validated
public class CaspRegisterController {

    private final CaspRegistryService service;
    private final CaspRegisterImportService importService;

    CaspRegisterController(CaspRegistryService service, CaspRegisterImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping
    public List<CaspAuthorizationResponse> list() {
        return service.findAll().stream().map(CaspAuthorizationResponse::from).toList();
    }

    /**
     * Idempotent upsert keyed by {@code vaspDid}. Step-up plus a second approver (6-28): a register edit can
     * unblock a counterparty, so it is a four-eyes action; lifting a blocking status needs an admin approver.
     */
    @PutMapping
    @RequiresStepUp(requireSecondApprover = true, reason = "CASP_REGISTER_EDIT")
    public CaspAuthorizationResponse upsert(@RequestBody @Valid CaspAuthorizationRequest request, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        CaspAuthorization entry = new CaspAuthorization();
        entry.setVaspDid(request.vaspDid().trim());
        entry.setLegalName(request.legalName().trim());
        entry.setLei(trimToNull(request.lei()));
        entry.setHomeMemberState(trimToNull(request.homeMemberState()));
        entry.setCountry(trimToNull(request.homeMemberState()));
        entry.setStatus(request.status());
        entry.setAuthorizationId(trimToNull(request.authorizationId()));
        entry.setValidFrom(request.validFrom());
        entry.setValidUntil(request.validUntil());
        entry.setSource(trimToNull(request.source()));
        entry.setNotes(trimToNull(request.notes()));
        return CaspAuthorizationResponse.from(service.upsert(entry,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId));
    }

    /**
     * Step 1 of a bulk import (typically a transformed ESMA MiCA register export, columns in
     * {@link CaspRegisterImportService}): returns the diff (created / updated / status changes) and a
     * {@code diffDigest}; nothing is written.
     */
    @PostMapping(value = "/import/preview", consumes = {"text/csv", "text/plain"})
    public CaspRegisterImportService.ImportResult previewImport(@RequestBody @Size(max = 5_000_000) String csv) {
        return importService.previewCsv(csv, "CSV import " + LocalDate.now());
    }

    /** Step 2: commits the previewed CSV; requires the preview's {@code diffDigest} and step-up + second approver. */
    @PostMapping(value = "/import", consumes = {"text/csv", "text/plain"})
    @RequiresStepUp(requireSecondApprover = true, reason = "CASP_REGISTER_IMPORT")
    public CaspRegisterImportService.ImportResult importCsv(
            @RequestBody @Size(max = 5_000_000) String csv, @RequestParam String diffDigest, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return importService.importCsv(csv, "CSV import " + LocalDate.now(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"),
                approverId, true, diffDigest);
    }

    @DeleteMapping("/{id}")
    @RequiresStepUp(requireSecondApprover = true, reason = "CASP_REGISTER_DELETE")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        service.delete(id, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.noContent().build();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record CaspAuthorizationRequest(
            @NotBlank @Size(max = 255) String vaspDid,
            @NotBlank @Size(max = 1000) String legalName,
            @Size(max = 20) String lei,
            @Pattern(regexp = "^[A-Z]{2}$", message = "Must be an ISO 3166-1 alpha-2 code")
            String homeMemberState,
            @NotNull CaspAuthorizationStatus status,
            @Size(max = 1000) String authorizationId,
            LocalDate validFrom,
            LocalDate validUntil,
            @Size(max = 2000) String source,
            @Size(max = 10_000) String notes) {

        public CaspAuthorizationRequest {
            if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
                throw new IllegalArgumentException("validUntil must not be before validFrom");
            }
        }
    }

    public record CaspAuthorizationResponse(
            UUID id,
            String vaspDid,
            String legalName,
            String lei,
            String homeMemberState,
            CaspAuthorizationStatus status,
            String authorizationId,
            LocalDate validFrom,
            LocalDate validUntil,
            String source,
            String notes) {

        static CaspAuthorizationResponse from(CaspAuthorization c) {
            return new CaspAuthorizationResponse(c.getId(), c.getVaspDid(), c.getLegalName(),
                    c.getLei(), c.getHomeMemberState(), c.getStatus(), c.getAuthorizationId(),
                    c.getValidFrom(), c.getValidUntil(), c.getSource(), c.getNotes());
        }
    }
}
