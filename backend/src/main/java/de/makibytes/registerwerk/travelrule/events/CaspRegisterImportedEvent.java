package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A bulk CSV import into the CASP register completed. {@code importId} identifies the batch
 * itself (there is no single row to key this event on) — {@code details} carries the
 * created/updated/failed counts and source label.
 */
public record CaspRegisterImportedEvent(UUID importId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CASP_REGISTER_IMPORTED"; }
    public String subjectType() { return "CaspRegisterImport"; }
    public UUID   subjectId()   { return importId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
