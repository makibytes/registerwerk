package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

public record MintControlRuleDeactivatedEvent(
        UUID ruleId, UUID deploymentId, UUID actorId, String actorRole
) implements AuditableEvent {
    public String eventType()   { return "MINT_CONTROL_RULE_DEACTIVATED"; }
    public String subjectType() { return "MintControlRule"; }
    public UUID   subjectId()   { return ruleId; }
    public Map<String, Object> payload() {
        return Map.of("deploymentId", deploymentId.toString());
    }
}
