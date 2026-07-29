package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.events.RegReportTransportEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Shared persistence helper for draft/unvalidated regulatory-report exports.
 * Encapsulates the INSERT into regreport_submission used by all reporting services, and —
 * since every status transition funnels through here — is the single chokepoint from which
 * {@link RegReportTransportEvent} outcomes are published, rather than
 * duplicating event-construction in every reporting service.
 */
@Component
class RegReportSubmissions {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    RegReportSubmissions(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
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

    /** @param generatedBy the triggering REGISTRY_ADMIN, or null for the nightly scheduled run */
    UUID persist(String reportType, String jurisdiction,
                 LocalDate periodStart, LocalDate periodEnd, UUID generatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO regreport_submission
              (id, report_type, jurisdiction, status,
               reporting_period_start, reporting_period_end, generated_by, created_at, updated_at)
            VALUES (?, ?, ?, 'DRAFT_UNVALIDATED', ?, ?, ?, now(), now())
            """, id, reportType, jurisdiction, periodStart, periodEnd, generatedBy);
        return id;
    }

    void recordDocumentKey(UUID submissionId, String s3Key) {
        jdbc.update("""
            UPDATE regreport_submission
            SET document_s3_key = ?, updated_at = now()
            WHERE id = ?
            """, s3Key, submissionId);
    }

    /** Binds the stored draft to its content; it does not prove filing or acceptance. */
    void recordDocumentHash(UUID submissionId, byte[] sha256Hash) {
        jdbc.update("""
            UPDATE regreport_submission
            SET document_hash = ?, updated_at = now()
            WHERE id = ?
            """, sha256Hash, submissionId);
    }

    void markTransportedUnverified(UUID submissionId, String transportRef, UUID actorId, String actorRole) {
        jdbc.update("""
            UPDATE regreport_submission
            SET status = 'TRANSPORTED_UNVERIFIED', transport_ref = ?, transported_at = now(), updated_at = now()
            WHERE id = ?
            """, transportRef, submissionId);
        publishTransportEvent(submissionId, transportRef, null, actorId, actorRole,
                "TRANSPORTED_UNVERIFIED");
    }

    void markNotTransported(UUID submissionId, String reason, UUID actorId, String actorRole) {
        jdbc.update("""
            UPDATE regreport_submission
            SET status = 'NOT_TRANSPORTED', transport_error = ?, updated_at = now()
            WHERE id = ?
            """, reason, submissionId);
        publishTransportEvent(submissionId, null, reason, actorId, actorRole, "NOT_TRANSPORTED");
    }

    void markTransportFailed(UUID submissionId, String reason, UUID actorId, String actorRole) {
        jdbc.update("""
            UPDATE regreport_submission
            SET status = 'TRANSPORT_FAILED', transport_error = ?, updated_at = now()
            WHERE id = ?
            """, reason, submissionId);
        publishTransportEvent(submissionId, null, reason, actorId, actorRole, "TRANSPORT_FAILED");
    }

    private void publishTransportEvent(UUID submissionId, String transportRef, String reason,
                                       UUID actorId, String actorRole, String status) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("status", status);
        details.put("transportRef", transportRef != null ? transportRef : "");
        details.put("reason", reason != null ? reason : "");
        details.put("authorityReceiptVerified", false);
        events.publishEvent(new RegReportTransportEvent(submissionId, actorId, actorRole, details));
    }
}
