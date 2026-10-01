package de.makibytes.registerwerk.dora.web;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncidentReport;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpBearerAccepted;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.internal.DoraService;
import de.makibytes.registerwerk.shared.CsvWriter;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
        return ResponseEntity.ok(doraService.listOpen().stream()
                .map(i -> IctIncidentResponse.from(i, doraService.closeBlockers(i), List.of())).toList());
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IctIncidentResponse> getIncident(@PathVariable UUID id) {
        return ResponseEntity.ok(detail(id));
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
                req.awarenessAt(), req.classificationReason(), req.classificationCriteria(),
                req.sourceEventType(), req.sourceEventRef(), actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(IctIncidentResponse.from(incident, doraService.closeBlockers(incident), List.of()));
    }

    /** (Re)classify: severity + mandatory reason (+ criteria). Withdrawing MAJOR also needs a second approver. */
    @PostMapping("/incidents/{id}/classify")
    @RequiresStepUp(reason = "DORA_INCIDENT_CLASSIFY")
    public ResponseEntity<IctIncidentResponse> classify(
            @PathVariable UUID id,
            @RequestBody @Valid ClassifyRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        doraService.classify(id, IctIncident.Severity.valueOf(req.severity()), req.reason(), req.criteria(), actorId);
        return ResponseEntity.ok(detail(id));
    }

    @PatchMapping("/incidents/{id}/status")
    // Closing a MAJOR incident goes through DualControlGate, which needs the caller's step-up token as
    // bearer; the marker lets StepUpTokenAsSessionGuard accept it here (other status updates need none).
    @StepUpBearerAccepted
    public ResponseEntity<IctIncidentResponse> updateStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateStatusRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        IctIncident incident = doraService.updateStatus(
                id, IctIncident.Status.valueOf(req.status()),
                req.rootCause(), req.remediationSteps(), actorId);
        return ResponseEntity.ok(detail(incident.getId()));
    }

    @PostMapping("/incidents/{id}/report-to-authority")
    public ResponseEntity<IctIncidentResponse> markReported(
            @PathVariable UUID id,
            @RequestBody @Valid ReportToAuthorityRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        IctIncidentReport.Type type = req.reportType() != null && !req.reportType().isBlank()
                ? IctIncidentReport.Type.valueOf(req.reportType())
                : Boolean.TRUE.equals(req.isFinalReport()) ? IctIncidentReport.Type.FINAL : IctIncidentReport.Type.INITIAL;
        doraService.markReportedToAuthority(id, type, req.authorityRef(), req.note(), req.submittedAt(), actorId);
        return ResponseEntity.ok(detail(id));
    }

    private IctIncidentResponse detail(UUID id) {
        IctIncident incident = doraService.getIncident(id);
        return IctIncidentResponse.from(incident, doraService.closeBlockers(incident), doraService.listReports(id));
    }

    /**
     * Major-incident authority-notification export (DORA Art. 19) — a key/value CSV covering the
     * general-information and classification fields the joint ESA incident-reporting RTS asks
     * for, populated from this record. This is <b>not the certified ESA submission format</b>
     * (this environment has no access to the authoritative RTS annexes / reporting portal
     * schema); it is a structured starting point compliance can adapt into the actual submission,
     * the same way {@code SteuerbescheinigungService} generates a tax-shaped document without
     * claiming to be the Finanzamt's own form.
     */
    @GetMapping(value = "/incidents/{id}/authority-report", produces = "text/csv")
    public ResponseEntity<String> exportIncidentAuthorityReport(@PathVariable UUID id) {
        IctIncident i = doraService.getIncident(id);
        List<String> header = List.of("field", "value");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Incident reference", i.getId()));
        rows.add(List.of("Category", i.getCategory()));
        rows.add(List.of("Severity", i.getSeverity()));
        rows.add(List.of("Status", i.getStatus()));
        rows.add(List.of("Title", i.getTitle()));
        rows.add(List.of("Description", nullToEmpty(i.getDescription())));
        rows.add(List.of("Entered in register at", i.getDetectedAt()));
        rows.add(List.of("Awareness time", i.getAwarenessAt()));
        rows.add(List.of("Classified as major at", nullToEmpty(i.getClassifiedAt())));
        rows.add(List.of("Classification reason", nullToEmpty(i.getClassificationReason())));
        rows.add(List.of("Classification criteria", nullToEmpty(i.getClassificationCriteria())));
        rows.add(List.of("Initial notification deadline, 4h from classification", nullToEmpty(i.getClassificationDeadline())));
        rows.add(List.of("Initial notification deadline, 24h from awareness", nullToEmpty(i.getInitialReportDeadline())));
        rows.add(List.of("Intermediate report deadline (72h after initial notification)", nullToEmpty(i.getIntermediateReportDeadline())));
        rows.add(List.of("Final report deadline (1 month from awareness)", nullToEmpty(i.getFinalReportDeadline())));
        rows.add(List.of("Initial report submitted at", nullToEmpty(i.getInitialReportedAt())));
        rows.add(List.of("Intermediate report submitted at", nullToEmpty(i.getIntermediateReportedAt())));
        rows.add(List.of("Final report submitted at", nullToEmpty(i.getFinalReportedAt())));
        rows.add(List.of("Authority reference (initial notification)", nullToEmpty(i.getAuthorityRef())));
        int n = 0;
        for (IctIncidentReport r : doraService.listReports(id)) {
            n++;
            rows.add(List.of("Report " + n + " (" + r.getReportType() + ")",
                    r.getSubmittedAt() + " ref=" + nullToEmpty(r.getAuthorityRef())
                            + (r.getNote() != null ? " note=" + r.getNote() : "")));
        }
        rows.add(List.of("Contained at", nullToEmpty(i.getContainedAt())));
        rows.add(List.of("Resolved at", nullToEmpty(i.getResolvedAt())));
        rows.add(List.of("Root cause", nullToEmpty(i.getRootCause())));
        rows.add(List.of("Remediation actions taken", nullToEmpty(i.getRemediationSteps())));
        rows = rows.stream().map(DoraController::safeRow).toList();
        String csv = CsvWriter.write(header, rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"dora-incident-report-" + id + ".csv\"")
                .body(csv);
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

    /**
     * ICT third-party provider register export (DORA Art. 28) — a CSV covering the entity-level
     * fields the EBA Register of Information ITS templates ask for (name, LEI, criticality,
     * country, contract dates, sub-outsourcing, SLA/RTO/RPO). This is <b>not the certified EBA RoI
     * XBRL/CSV taxonomy submission</b> (this environment has no access to the authoritative ITS
     * template definitions or their exact "B_xx.xx" field codes) — see the caveat on
     * {@link #exportIncidentAuthorityReport}, same reasoning.
     */
    @GetMapping(value = "/providers/register-export", produces = "text/csv")
    public ResponseEntity<String> exportProviderRegister() {
        List<String> header = List.of(
                "name", "category", "criticality", "lei", "country",
                "contractStart", "contractEnd", "subOutsourcing", "subOutsourcingDetails",
                "primaryContact", "slaAvailabilityPct", "rtoHours", "rpoHours",
                "notifiedAuthority", "notifiedAt", "notes");
        List<List<Object>> rows = doraService.listProviders().stream().map(p -> safeRow(List.<Object>of(
                p.getName(), nullToEmpty(p.getCategory()), p.getCriticality(),
                nullToEmpty(p.getLei()), nullToEmpty(p.getCountry()),
                nullToEmpty(p.getContractStart()), nullToEmpty(p.getContractEnd()),
                p.isSubOutsourcing() ? "Y" : "N", nullToEmpty(p.getSubOutsourcingDetails()),
                nullToEmpty(p.getPrimaryContact()), nullToEmpty(p.getSlaAvailabilityPct()),
                nullToEmpty(p.getRtoHours()), nullToEmpty(p.getRpoHours()),
                p.isNotifiedAuthority() ? "Y" : "N", nullToEmpty(p.getNotifiedAt()),
                nullToEmpty(p.getNotes())
        ))).toList();
        String csv = CsvWriter.write(header, rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"dora-register-of-information.csv\"")
                .body(csv);
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

    private static Object nullToEmpty(Object v) { return v == null ? "" : v; }

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?\\d+([.,]\\d+)?");
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?");

    private static List<Object> safeRow(List<Object> row) {
        return row.stream().map(DoraController::csvSafe).toList();
    }

    /**
     * CSV formula-injection guard for issuer/user-supplied text: a text cell that spreadsheet software
     * would evaluate (leading {@code = + - @}, tab or CR) is prefixed with an apostrophe. Real numbers and
     * ISO dates/instants, and non-text values, are left untouched.
     */
    static Object csvSafe(Object v) {
        if (!(v instanceof String s) || s.isEmpty()) return v;
        char c = s.charAt(0);
        if (c != '=' && c != '+' && c != '-' && c != '@' && c != '\t' && c != '\r') return v;
        if (PLAIN_NUMBER.matcher(s).matches() || ISO_DATE_TIME.matcher(s).matches()) return v;
        return "'" + s;
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
            /** When the operator became aware; default now, never in the future, immutable afterwards. */
            Instant awarenessAt,
            /** Required when severity is MAJOR. */
            String classificationReason,
            Map<String, Object> classificationCriteria,
            String sourceEventType,
            UUID sourceEventRef
    ) {}

    public record ClassifyRequest(
            @NotBlank String severity,
            @NotBlank String reason,
            Map<String, Object> criteria
    ) {}

    public record UpdateStatusRequest(
            @NotBlank String status,
            String rootCause,
            String remediationSteps
    ) {}

    /** {@code reportType} INITIAL|INTERMEDIATE|FINAL; legacy {@code isFinalReport} still accepted when it is absent. */
    public record ReportToAuthorityRequest(
            @NotBlank String authorityRef,
            Boolean isFinalReport,
            String reportType,
            String note,
            Instant submittedAt
    ) {}

    public record IctIncidentReportView(
            UUID id, String reportType, String submittedAt, String authorityRef, UUID submittedBy,
            String note, String recordedAt
    ) {
        static IctIncidentReportView from(IctIncidentReport r) {
            return new IctIncidentReportView(r.getId(), r.getReportType().name(), r.getSubmittedAt().toString(),
                    r.getAuthorityRef(), r.getSubmittedBy(), r.getNote(), r.getRecordedAt().toString());
        }
    }

    /**
     * {@code closeBlockers}: why CLOSED is currently refused (CLASSIFICATION_PENDING, INITIAL_REPORT_MISSING,
     * FINAL_REPORT_MISSING); closing a MAJOR incident additionally needs step-up and a second approver.
     * {@code reports} is filled on the detail/mutation responses only.
     */
    public record IctIncidentResponse(
            UUID id, String title, String category, String severity, String status,
            String detectedAt, String awarenessAt, String classifiedAt, String classificationReason,
            Map<String, Object> classificationCriteria, boolean classificationPending,
            String classificationDeadline, String initialReportDeadline, String intermediateReportDeadline,
            String finalReportDeadline,
            String initialReportedAt, String intermediateReportedAt, String finalReportedAt, String authorityRef,
            String downgradedAt, String downgradeReason, List<String> closeBlockers,
            String rootCause, String remediationSteps, String resolvedAt,
            List<IctIncidentReportView> reports
    ) {
        static IctIncidentResponse from(IctIncident i, List<String> closeBlockers, List<IctIncidentReport> reports) {
            return new IctIncidentResponse(
                    i.getId(), i.getTitle(),
                    i.getCategory().name(), i.getSeverity().name(), i.getStatus().name(),
                    ts(i.getDetectedAt()), ts(i.getAwarenessAt()), ts(i.getClassifiedAt()), i.getClassificationReason(),
                    i.getClassificationCriteria(), i.isClassificationPending(),
                    ts(i.getClassificationDeadline()), ts(i.getInitialReportDeadline()),
                    ts(i.getIntermediateReportDeadline()), ts(i.getFinalReportDeadline()),
                    ts(i.getInitialReportedAt()), ts(i.getIntermediateReportedAt()), ts(i.getFinalReportedAt()),
                    i.getAuthorityRef(), ts(i.getDowngradedAt()), i.getDowngradeReason(), closeBlockers,
                    i.getRootCause(), i.getRemediationSteps(), ts(i.getResolvedAt()),
                    reports.stream().map(IctIncidentReportView::from).toList());
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
