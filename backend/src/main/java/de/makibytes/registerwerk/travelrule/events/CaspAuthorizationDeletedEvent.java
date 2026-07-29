package de.makibytes.registerwerk.travelrule.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** A CASP register entry was deleted from the counterparty MiCA authorization register. */
public record CaspAuthorizationDeletedEvent(UUID authorizationId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CASP_AUTHORIZATION_DELETED"; }
    public String subjectType() { return "CaspAuthorization"; }
    public UUID   subjectId()   { return authorizationId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
