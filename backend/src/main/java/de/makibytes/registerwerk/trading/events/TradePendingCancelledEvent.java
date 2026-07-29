package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when a still-PENDING trade is cancelled by either the buyer or seller, mirroring
 * {@code cancelListing}'s equivalent {@link TradeListingCancelledEvent}.
 */
public record TradePendingCancelledEvent(UUID executionId, UUID actorId, String actorRole, String reason)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PENDING_CANCELLED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of("reason", reason != null ? reason : ""); }
}
