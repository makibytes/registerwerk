package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when the SELLING company disputes a buyer's declared payment (claims it was never
 * received) — the trade fails and the listing's reserved quantity is released back for sale.
 */
public record TradePaymentDisputedEvent(UUID executionId, UUID actorId, String actorRole, String reason)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PAYMENT_DISPUTED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of("reason", reason != null ? reason : ""); }
}
