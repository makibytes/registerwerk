package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired by the daily {@code timeoutStuckPendingTrades} job for every PENDING trade it expires
 * — previously this unattended, no-human-actor state change was only
 * logged, leaving no {@code audit_event} trail for what is otherwise a silent status flip.
 */
public record TradePendingTimedOutEvent(UUID executionId, String actorRole, long pendingTimeoutHours)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PENDING_TIMED_OUT"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public UUID   actorId()     { return null; }
    public Map<String, Object> payload() { return Map.of("pendingTimeoutHours", pendingTimeoutHours); }
}
