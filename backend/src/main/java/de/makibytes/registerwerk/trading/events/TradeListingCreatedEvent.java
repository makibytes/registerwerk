package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Payload enriched with listing economics. */
public record TradeListingCreatedEvent(
        UUID listingId, UUID actorId, String actorRole, UUID assetId,
        UUID sellerHolderId, BigDecimal quantity, BigDecimal pricePerUnit
) implements AuditableEvent {
    public String eventType()   { return "TRADE_LISTING_CREATED"; }
    public String subjectType() { return "TradeListing"; }
    public UUID   subjectId()   { return listingId; }
    public Map<String, Object> payload() {
        Map<String, Object> map = new HashMap<>();
        map.put("assetId", assetId != null ? assetId.toString() : "");
        map.put("sellerHolderId", sellerHolderId != null ? sellerHolderId.toString() : "");
        map.put("quantity", quantity != null ? quantity.toPlainString() : "");
        map.put("pricePerUnit", pricePerUnit != null ? pricePerUnit.toPlainString() : "");
        return map;
    }
}
