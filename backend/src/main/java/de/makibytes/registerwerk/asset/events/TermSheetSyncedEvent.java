package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TermSheetSyncedEvent(UUID documentId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "TERM_SHEET_SYNCED"; }
    public String subjectType() { return "AssetDocument"; }
    public UUID   subjectId()   { return documentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
