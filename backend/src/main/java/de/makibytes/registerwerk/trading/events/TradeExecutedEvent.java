package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payload enriched with trade economics — previously carried only
 * {@code listingId}, so reconstructing what was actually traded (quantity, price, counterparty)
 * from {@code audit_event} alone required joining back to the live {@code trade_execution} row,
 * which later mutations (cancel/refund) can change.
 */
public record TradeExecutedEvent(
        UUID executionId, UUID actorId, String actorRole, UUID listingId, UUID assetId,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal totalPrice,
        UUID buyerEntityId, UUID sellerEntityId
) implements AuditableEvent {
    public String eventType()   { return "TRADE_EXECUTED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() {
        Map<String, Object> map = new HashMap<>();
        map.put("listingId", listingId != null ? listingId.toString() : "");
        map.put("assetId", assetId != null ? assetId.toString() : "");
        map.put("quantity", quantity != null ? quantity.toPlainString() : "");
        map.put("unitPrice", unitPrice != null ? unitPrice.toPlainString() : "");
        map.put("totalPrice", totalPrice != null ? totalPrice.toPlainString() : "");
        map.put("buyerEntityId", buyerEntityId != null ? buyerEntityId.toString() : "");
        map.put("sellerEntityId", sellerEntityId != null ? sellerEntityId.toString() : "");
        return map;
    }
}
