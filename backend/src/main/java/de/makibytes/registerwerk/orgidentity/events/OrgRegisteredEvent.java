package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record OrgRegisteredEvent(UUID orgRegistrationId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ORG_REGISTERED"; }
    public String subjectType() { return "OrgRegistration"; }
    public UUID   subjectId()   { return orgRegistrationId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
