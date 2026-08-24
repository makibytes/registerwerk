package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when the buyer declares payment on a PENDING trade. This no longer credits the
 * register directly — it only starts the clock on {@link TradePaymentConfirmedEvent} /
 * {@link TradePaymentDisputedEvent}, which the SELLING company must trigger.
 */
public record TradePaymentDeclaredEvent(UUID executionId, UUID actorId, String actorRole, String paymentReference)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PAYMENT_DECLARED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return Map.of("paymentReference", paymentReference != null ? paymentReference : ""); }
}
