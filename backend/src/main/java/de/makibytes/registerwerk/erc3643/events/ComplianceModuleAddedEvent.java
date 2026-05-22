package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ComplianceModuleAddedEvent(UUID suiteId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "COMPLIANCE_MODULE_ADDED"; }
    public String subjectType() { return "Erc3643Suite"; }
    public UUID   subjectId()   { return suiteId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
