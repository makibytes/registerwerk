package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record InvestorRemovedEvent(UUID suiteId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ERC3643_INVESTOR_REMOVED"; }
    public String subjectType() { return "Erc3643Suite"; }
    public UUID   subjectId()   { return suiteId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
