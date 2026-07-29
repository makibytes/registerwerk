package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when an already-SETTLED trade is reversed/refunded after the fact — a step-up +
 * dual-control REGISTRY_ADMIN action. {@code dualControlApproverId} is populated from the same
 * aspect-validated second approver every other dual-control action in this codebase records
 * (see {@code kyc.internal.SperrvermerkService}).
 */
public record TradeRefundedEvent(
        UUID executionId, UUID actorId, String actorRole, String reason, UUID dualControlApproverId
) implements AuditableEvent {
    public String eventType()   { return "TRADE_REFUNDED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of("reason", reason != null ? reason : ""); }

    @Override
    public UUID dualControlApproverId() { return dualControlApproverId; }
}
