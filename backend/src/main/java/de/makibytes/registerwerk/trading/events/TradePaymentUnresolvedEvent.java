package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A trade with a declared payment could not be settled or failed automatically and now waits in
 * the operator queue (PAYMENT_UNRESOLVED): seller dispute, seller-confirmation timeout, a
 * settlement gate failing at confirm time, or the seller's register entry / the asset / a party
 * becoming ineligible. The units stay reserved. Both parties and the operator are notified.
 * {@code source} is one of SELLER_DISPUTE, TIMEOUT, GATE_FAILED_AT_CONFIRM, REGISTER_ENTRY_REMOVED,
 * ASSET_SUSPENDED, ASSET_REDEEMED, CUSTOMER_OFFBOARDED.
 */
public record TradePaymentUnresolvedEvent(UUID executionId, UUID actorId, String actorRole, String source, String reason,
                                          UUID buyerEntityId, UUID sellerEntityId)
        implements AuditableEvent {
    public String eventType()   { return "TRADE_PAYMENT_UNRESOLVED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() {
        return Map.of("source", source != null ? source : "", "reason", reason != null ? reason : "");
    }
}
