package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Operator resolution of a PAYMENT_UNRESOLVED trade (step-up + second approver). {@code action}:
 * FORCE_SETTLE, RECORD_RETURN_OF_FUNDS or RELEASE.
 */
public record TradeUnresolvedResolvedEvent(UUID executionId, UUID actorId, String actorRole, String action,
                                           String legalBasis, String note, UUID dualControlApproverId)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_UNRESOLVED_RESOLVED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() {
        return Map.of("action", action, "legalBasis", legalBasis != null ? legalBasis : "",
                "note", note != null ? note : "");
    }
}
