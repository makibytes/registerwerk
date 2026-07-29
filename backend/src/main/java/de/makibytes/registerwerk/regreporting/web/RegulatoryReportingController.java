package de.makibytes.registerwerk.regreporting.web;

import de.makibytes.registerwerk.regreporting.internal.Dac8ExportService;
import de.makibytes.registerwerk.regreporting.internal.MifirReportingService;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST API for opt-in, non-production draft reporting exports.
 * Outputs are DRAFT_UNVALIDATED and transport state is not authority filing state.
 * Accessible to REGISTRY_ADMIN and COMPLIANCE_OFFICER.
 */
@RestController
@RequestMapping("/api/v1/regulatory-reporting")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
public class RegulatoryReportingController {

    private final Dac8ExportService dac8Service;
    private final MifirReportingService mifirService;
    private final JdbcTemplate jdbc;

    public RegulatoryReportingController(Dac8ExportService dac8Service,
                                          MifirReportingService mifirService,
                                          JdbcTemplate jdbc) {
        this.dac8Service = dac8Service;
        this.mifirService = mifirService;
        this.jdbc = jdbc;
    }

    /** List recent draft exports and transport-only outcomes (all types). */
    @GetMapping("/submissions")
    public ResponseEntity<List<Map<String, Object>>> listSubmissions(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, report_type, jurisdiction, status,
                       reporting_period_start, reporting_period_end,
                       transported_at, transport_ref, transport_error, created_at
                  FROM regreport_submission
                 ORDER BY created_at DESC
                 LIMIT ?
                """, limit);
        return ResponseEntity.ok(rows);
    }

    /** Trigger an opt-in DRAFT_UNVALIDATED DAC8/CARF-like export for a tax year. */
    @PostMapping("/dac8/generate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Map<String, String>> generateDac8(
            @RequestParam(required = false) Integer taxYear, Authentication auth) {
        int year = taxYear != null ? taxYear : LocalDate.now().getYear() - 1;
        dac8Service.generateAnnualCarf(year, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.accepted().body(Map.of(
                "status", "DRAFT_EXPORT_REQUESTED",
                "taxYear", String.valueOf(year),
                "message", "Draft/unvalidated export processed. Transport does not prove filing; check /submissions."));
    }

    /** Trigger an opt-in DRAFT_UNVALIDATED MiFIR-like export for a date. */
    @PostMapping("/mifir/generate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Map<String, String>> generateMifir(
            @RequestParam(required = false) String reportingDate, Authentication auth) {
        LocalDate date = reportingDate != null ? LocalDate.parse(reportingDate) : LocalDate.now().minusDays(1);
        mifirService.generateDailyReport(date, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.accepted().body(Map.of(
                "status", "DRAFT_EXPORT_REQUESTED",
                "reportingDate", date.toString(),
                "message", "Draft/unvalidated export processed. Transport does not prove filing; check /submissions."));
    }
}
