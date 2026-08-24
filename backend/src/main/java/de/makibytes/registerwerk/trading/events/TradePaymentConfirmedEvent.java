package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when the SELLING company independently confirms receipt of a buyer's declared payment —
 * the point at which the register is actually credited. {@code actorId}/{@code actorRole}
 * identify the seller-side confirmer, distinct from {@link TradeExecutedEvent}'s buyer actor.
 */
public record TradePaymentConfirmedEvent(UUID executionId, UUID actorId, String actorRole)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PAYMENT_CONFIRMED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of(); }
}
