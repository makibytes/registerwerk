package de.makibytes.registerwerk.regreporting.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Shared persistence helper for regulatory report submissions.
 * Encapsulates the INSERT into regreport_submission used by all reporting services.
 */
@Component
class RegReportSubmissions {

    private final JdbcTemplate jdbc;

    RegReportSubmissions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Escapes characters unsafe in XML text content. Apply to every DB-sourced value. */
    static String esc(Object v) {
        if (v == null) return "";
        return v.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    UUID persist(String reportType, String jurisdiction,
                 LocalDate periodStart, LocalDate periodEnd) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO regreport_submission
              (id, report_type, jurisdiction, status,
               reporting_period_start, reporting_period_end, created_at, updated_at)
            VALUES (?, ?, ?, 'READY', ?, ?, now(), now())
            """, id, reportType, jurisdiction, periodStart, periodEnd);
        return id;
    }
}
