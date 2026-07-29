package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A CASP register entry (MiCA authorization status) was created or updated. */
public record CaspAuthorizationUpsertedEvent(UUID authorizationId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CASP_AUTHORIZATION_UPSERTED"; }
    public String subjectType() { return "CaspAuthorization"; }
    public UUID   subjectId()   { return authorizationId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
