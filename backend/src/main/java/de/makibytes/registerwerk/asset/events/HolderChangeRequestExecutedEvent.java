package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Fired when the operator executes an issuer's register-entry change request. */
public record HolderChangeRequestExecutedEvent(UUID requestId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "HOLDER_CHANGE_EXECUTED"; }
    public String subjectType() { return "HolderChangeRequest"; }
    public UUID   subjectId()   { return requestId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
