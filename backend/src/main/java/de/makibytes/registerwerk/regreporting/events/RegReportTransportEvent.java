package de.makibytes.registerwerk.regreporting.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Records a transport-only outcome for a draft/unvalidated reporting document.
 * This event never represents authority submission, acceptance, or rejection.
 */
public record RegReportTransportEvent(UUID submissionId, UUID actorId, String actorRole,
                                      Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "REGULATORY_REPORT_DRAFT_TRANSPORT"; }
    public String subjectType() { return "RegReportSubmission"; }
    public UUID subjectId()     { return submissionId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
