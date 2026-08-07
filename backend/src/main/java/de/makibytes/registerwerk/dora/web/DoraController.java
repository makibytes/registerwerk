package de.makibytes.registerwerk.dora.web;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.internal.DoraService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DORA ICT resilience management REST API.
 * Art. 17 — ICT incident lifecycle.
 * Art. 28 — ICT third-party provider register.
 */
@RestController
@RequestMapping("/api/v1/dora")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class DoraController {

    private final DoraService doraService;

    public DoraController(DoraService doraService) {
        this.doraService = doraService;
    }

    // ── ICT Incidents ──────────────────────────────────────────────────────────

    @GetMapping("/incidents")
    public ResponseEntity<List<IctIncidentResponse>> listOpenIncidents() {
        return ResponseEntity.ok(doraService.listOpen().stream().map(IctIncidentResponse::from).toList());
    }

    @PostMapping("/incidents")
    public ResponseEntity<IctIncidentResponse> reportIncident(
            @RequestBody @Valid ReportIncidentRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        IctIncident incident = doraService.reportIncident(
                req.title(), req.description(),
                IctIncident.Category.valueOf(req.category()),
                IctIncident.Severity.valueOf(req.severity()),
                req.sourceEventType(), req.sourceEventRef(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(IctIncidentResponse.from(incident));
    }

    @PatchMapping("/incidents/{id}/status")
    public ResponseEntity<IctIncidentResponse> updateStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateStatusRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        IctIncident incident = doraService.updateStatus(
                id, IctIncident.Status.valueOf(req.status()),
                req.rootCause(), req.remediationSteps(), actorId);
        return ResponseEntity.ok(IctIncidentResponse.from(incident));
    }

    @PostMapping("/incidents/{id}/report-to-authority")
    public ResponseEntity<IctIncidentResponse> markReported(
            @PathVariable UUID id,
            @RequestBody @Valid ReportToAuthorityRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        IctIncident incident = doraService.markReportedToAuthority(id, req.authorityRef(), req.isFinalReport(), actorId);
        return ResponseEntity.ok(IctIncidentResponse.from(incident));
    }

    // ── Third-Party Provider Register ─────────────────────────────────────────

    @GetMapping("/providers")
    public ResponseEntity<List<ThirdPartyProviderResponse>> listProviders() {
        return ResponseEntity.ok(doraService.listProviders().stream().map(ThirdPartyProviderResponse::from).toList());
    }

    @GetMapping("/providers/expiring")
    public ResponseEntity<List<ThirdPartyProviderResponse>> listExpiringContracts() {
        return ResponseEntity.ok(doraService.listExpiringContracts().stream().map(ThirdPartyProviderResponse::from).toList());
    }

    @PostMapping("/providers")
    public ResponseEntity<ThirdPartyProviderResponse> createProvider(
            @RequestBody @Valid CreateProviderRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ThirdPartyProvider provider = doraService.createProvider(
                req.name(), req.category(),
                req.criticality() != null ? ThirdPartyProvider.Criticality.valueOf(req.criticality()) : null,
                req.lei(), req.country(), req.contractStart(), req.contractEnd(),
                Boolean.TRUE.equals(req.subOutsourcing()), req.subOutsourcingDetails(), req.primaryContact(),
                req.slaAvailabilityPct(), req.rtoHours(), req.rpoHours(), req.notes(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ThirdPartyProviderResponse.from(provider));
    }

    @PatchMapping("/providers/{id}")
    public ResponseEntity<ThirdPartyProviderResponse> updateProvider(
            @PathVariable UUID id,
            @RequestBody @Valid CreateProviderRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ThirdPartyProvider provider = doraService.updateProvider(
                id, req.name(), req.category(),
                req.criticality() != null ? ThirdPartyProvider.Criticality.valueOf(req.criticality()) : null,
                req.lei(), req.country(), req.contractStart(), req.contractEnd(),
                Boolean.TRUE.equals(req.subOutsourcing()), req.subOutsourcingDetails(), req.primaryContact(),
                req.slaAvailabilityPct(), req.rtoHours(), req.rpoHours(),
                Boolean.TRUE.equals(req.notifiedAuthority()), req.notes(), actorId);
        return ResponseEntity.ok(ThirdPartyProviderResponse.from(provider));
    }

    // ── Resilience Testing ─────────────────────────────────────────────────────

    @GetMapping("/resilience-tests")
    public ResponseEntity<List<ResilienceTestResponse>> listResilienceTests() {
        return ResponseEntity.ok(doraService.listResilienceTests().stream().map(ResilienceTestResponse::from).toList());
    }

    @GetMapping("/resilience-tests/overdue")
    public ResponseEntity<List<ResilienceTestResponse>> listOverdueResilienceTests() {
        return ResponseEntity.ok(doraService.listOverdueResilienceTests().stream().map(ResilienceTestResponse::from).toList());
    }

    @PostMapping("/resilience-tests")
    public ResponseEntity<ResilienceTestResponse> recordResilienceTest(
            @RequestBody @Valid RecordResilienceTestRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ResilienceTest test = doraService.recordResilienceTest(
                ResilienceTest.TestType.valueOf(req.testType()), req.scope(),
                Boolean.TRUE.equals(req.tlptRequired()), req.thirdPartyProviderId(),
                req.performedAt(), req.nextDueDate(),
                ResilienceTest.Result.valueOf(req.result()), req.findings(),
                req.testerName(), req.reportRef(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResilienceTestResponse.from(test));
    }

    @PatchMapping("/resilience-tests/{id}")
    public ResponseEntity<ResilienceTestResponse> updateResilienceTest(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateResilienceTestRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ResilienceTest test = doraService.updateResilienceTestResult(
                id, ResilienceTest.Result.valueOf(req.result()), req.findings(), req.reportRef(), actorId);
        return ResponseEntity.ok(ResilienceTestResponse.from(test));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CreateProviderRequest(
            @NotBlank String name,
            String category,
            String criticality,
            String lei,
            String country,
            LocalDate contractStart,
            LocalDate contractEnd,
            Boolean subOutsourcing,
            String subOutsourcingDetails,
            String primaryContact,
            BigDecimal slaAvailabilityPct,
            Integer rtoHours,
            Integer rpoHours,
            Boolean notifiedAuthority,
            String notes
    ) {}

    public record UpdateResilienceTestRequest(
            @NotBlank String result,
            String findings,
            String reportRef
    ) {}

    public record ReportIncidentRequest(
            @NotBlank String title,
            String description,
            @NotBlank String category,
            @NotBlank String severity,
            String sourceEventType,
            UUID sourceEventRef
    ) {}

    public record UpdateStatusRequest(
            @NotBlank String status,
            String rootCause,
            String remediationSteps
    ) {}

    public record ReportToAuthorityRequest(
            @NotBlank String authorityRef,
            @NotNull Boolean isFinalReport
    ) {}

    public record IctIncidentResponse(
            UUID id, String title, String category, String severity, String status,
            String detectedAt, String initialReportDeadline, String finalReportDeadline,
            String initialReportedAt, String finalReportedAt, String authorityRef,
            String rootCause, String remediationSteps, String resolvedAt
    ) {
        static IctIncidentResponse from(IctIncident i) {
            return new IctIncidentResponse(
                    i.getId(), i.getTitle(),
                    i.getCategory().name(), i.getSeverity().name(), i.getStatus().name(),
                    ts(i.getDetectedAt()), ts(i.getInitialReportDeadline()), ts(i.getFinalReportDeadline()),
                    ts(i.getInitialReportedAt()), ts(i.getFinalReportedAt()),
                    i.getAuthorityRef(), i.getRootCause(), i.getRemediationSteps(),
                    ts(i.getResolvedAt()));
        }
        private static String ts(java.time.Instant t) { return t != null ? t.toString() : null; }
    }

    public record ThirdPartyProviderResponse(
            UUID id, String name, String category, String criticality,
            String lei, String country, String contractStart, String contractEnd,
            boolean subOutsourcing, String subOutsourcingDetails, String primaryContact,
            BigDecimal slaAvailabilityPct, Integer rtoHours, Integer rpoHours,
            boolean notifiedAuthority, String notifiedAt, String notes
    ) {
        static ThirdPartyProviderResponse from(ThirdPartyProvider p) {
            return new ThirdPartyProviderResponse(
                    p.getId(), p.getName(), p.getCategory(), p.getCriticality().name(),
                    p.getLei(), p.getCountry(),
                    p.getContractStart() != null ? p.getContractStart().toString() : null,
                    p.getContractEnd() != null ? p.getContractEnd().toString() : null,
                    p.isSubOutsourcing(), p.getSubOutsourcingDetails(), p.getPrimaryContact(),
                    p.getSlaAvailabilityPct(), p.getRtoHours(), p.getRpoHours(),
                    p.isNotifiedAuthority(),
                    p.getNotifiedAt() != null ? p.getNotifiedAt().toString() : null,
                    p.getNotes());
        }
    }

    public record RecordResilienceTestRequest(
            @NotBlank String testType,
            @NotBlank String scope,
            Boolean tlptRequired,
            UUID thirdPartyProviderId,
            @NotNull LocalDate performedAt,
            LocalDate nextDueDate,
            @NotBlank String result,
            String findings,
            String testerName,
            String reportRef
    ) {}

    public record ResilienceTestResponse(
            UUID id, String testType, String scope, boolean tlptRequired,
            UUID thirdPartyProviderId, String performedAt, String nextDueDate,
            String result, String findings, String testerName, String reportRef
    ) {
        static ResilienceTestResponse from(ResilienceTest t) {
            return new ResilienceTestResponse(
                    t.getId(), t.getTestType().name(), t.getScope(), t.isTlptRequired(),
                    t.getThirdPartyProviderId(),
                    t.getPerformedAt() != null ? t.getPerformedAt().toString() : null,
                    t.getNextDueDate() != null ? t.getNextDueDate().toString() : null,
                    t.getResult().name(), t.getFindings(), t.getTesterName(), t.getReportRef());
        }
    }
}
