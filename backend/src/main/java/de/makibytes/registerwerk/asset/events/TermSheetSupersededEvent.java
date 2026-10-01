package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** An operator-approved amendment replaced the term sheet of an issued asset (6-34); the old version is kept. */
public record TermSheetSupersededEvent(UUID documentId, UUID actorId, String actorRole, UUID dualControlApproverId,
                                       Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "TERM_SHEET_SUPERSEDED"; }
    public String subjectType() { return "AssetDocument"; }
    public UUID   subjectId()   { return documentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
