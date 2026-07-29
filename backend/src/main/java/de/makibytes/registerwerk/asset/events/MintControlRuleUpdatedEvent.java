package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record MintControlRuleUpdatedEvent(
        UUID ruleId, UUID deploymentId, UUID actorId, String actorRole,
        String targetAddress, String ruleType, BigDecimal maxAmount
) implements AuditableEvent {
    public String eventType()   { return "MINT_CONTROL_RULE_UPDATED"; }
    public String subjectType() { return "MintControlRule"; }
    public UUID   subjectId()   { return ruleId; }
    public Map<String, Object> payload() {
        return Map.of(
                "deploymentId", deploymentId.toString(),
                "targetAddress", targetAddress,
                "ruleType", ruleType,
                "maxAmount", maxAmount != null ? maxAmount.toString() : "UNBOUNDED"
        );
    }
}
