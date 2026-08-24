package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

public record InvestorLimitDeletedEvent(UUID limitId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "INVESTOR_LIMIT_DELETED"; }
    public String subjectType() { return "InvestorLimit"; }
    public UUID subjectId()     { return limitId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
