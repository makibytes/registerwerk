package de.makibytes.registerwerk.dora.web;

import de.makibytes.registerwerk.dora.api.IctIncident;
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
            @RequestBody @Valid ReportToAuthorityRequest req) {
        IctIncident incident = doraService.markReportedToAuthority(id, req.authorityRef(), req.isFinalReport());
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

    // ── DTOs ──────────────────────────────────────────────────────────────────

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
            String country, String contractEnd, boolean notifiedAuthority
    ) {
        static ThirdPartyProviderResponse from(ThirdPartyProvider p) {
            return new ThirdPartyProviderResponse(
                    p.getId(), p.getName(), p.getCategory(), p.getCriticality().name(),
                    p.getCountry(),
                    p.getContractEnd() != null ? p.getContractEnd().toString() : null,
                    p.isNotifiedAuthority());
        }
    }
}
