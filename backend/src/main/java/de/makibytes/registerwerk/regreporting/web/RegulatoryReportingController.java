package de.makibytes.registerwerk.regreporting.web;

import de.makibytes.registerwerk.regreporting.internal.Dac8ExportService;
import de.makibytes.registerwerk.regreporting.internal.MifirReportingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST API for regulatory report management and on-demand generation.
 * MiFIR RTS 22 (daily trade reporting), DAC8/CARF (annual crypto-asset reporting).
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

    /** List recent report submissions (all types). */
    @GetMapping("/submissions")
    public ResponseEntity<List<Map<String, Object>>> listSubmissions(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, report_type, jurisdiction, status,
                       reporting_period_start, reporting_period_end,
                       submitted_at, submission_ref, created_at
                  FROM regreport_submission
                 ORDER BY created_at DESC
                 LIMIT ?
                """, limit);
        return ResponseEntity.ok(rows);
    }

    /** Trigger on-demand DAC8/CARF export for a specific tax year. */
    @PostMapping("/dac8/generate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Map<String, String>> generateDac8(
            @RequestParam(required = false) Integer taxYear) {
        int year = taxYear != null ? taxYear : LocalDate.now().getYear() - 1;
        dac8Service.generateAnnualCarf();
        return ResponseEntity.accepted().body(Map.of(
                "status", "GENERATING",
                "taxYear", String.valueOf(year),
                "message", "DAC8/CARF export triggered. Check /submissions for results."));
    }

    /** Trigger on-demand MiFIR RTS-22 report for a specific date. */
    @PostMapping("/mifir/generate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Map<String, String>> generateMifir(
            @RequestParam(required = false) String reportingDate) {
        mifirService.generateDailyReport();
        String date = reportingDate != null ? reportingDate : LocalDate.now().minusDays(1).toString();
        return ResponseEntity.accepted().body(Map.of(
                "status", "GENERATING",
                "reportingDate", date,
                "message", "MiFIR RTS-22 report triggered. Check /submissions for results."));
    }
}
