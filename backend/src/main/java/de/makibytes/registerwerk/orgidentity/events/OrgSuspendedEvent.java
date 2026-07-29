package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record OrgSuspendedEvent(UUID orgRegistrationId, UUID legalEntityId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ORG_SUSPENDED"; }
    public String subjectType() { return "OrgRegistration"; }
    public UUID   subjectId()   { return orgRegistrationId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
