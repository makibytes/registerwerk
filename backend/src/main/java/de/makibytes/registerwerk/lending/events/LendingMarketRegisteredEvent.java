package de.makibytes.registerwerk.lending.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Audit event for operator registration of a new {@code EwpgRepoMarket} instance. */
public record LendingMarketRegisteredEvent(
        UUID marketId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType() { return "LENDING_MARKET_REGISTERED"; }
    public String subjectType() { return "LendingMarket"; }
    public UUID subjectId() { return marketId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
