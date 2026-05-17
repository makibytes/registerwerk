package de.makibytes.registerwerk.trading.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record TradeExecutedEvent(UUID executionId, UUID actorId, String actorRole, UUID listingId) implements AuditableEvent {
    public String eventType()   { return "TRADE_EXECUTED"; }
    public String subjectType() { return "TradeExecution"; }
    public UUID   subjectId()   { return executionId; }
    public Map<String, Object> payload() { return listingId != null ? Map.of("listingId", listingId.toString()) : Map.of(); }
}
