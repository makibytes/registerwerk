package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A compliance module bound to a suite (typically the legacy open-setter {@code EwpgComplianceModule})
 * was replaced by a current one: the new module was bound, configured, verified and back-filled with the
 * register holders before the old one was unbound, so the token was never without an enforcing module.
 */
public record ComplianceModuleReplacedEvent(UUID suiteId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "COMPLIANCE_MODULE_REPLACED"; }
    public String subjectType() { return "Erc3643Suite"; }
    public UUID   subjectId()   { return suiteId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
