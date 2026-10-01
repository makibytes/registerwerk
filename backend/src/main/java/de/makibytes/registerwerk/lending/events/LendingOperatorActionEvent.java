package de.makibytes.registerwerk.lending.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Audit event for an operator-initiated on-chain lending action (reconcileCollateral,
 * setBorrowPaused) or a system detection (balance guard). {@code type} is the suffix of the
 * audit event type, e.g. {@code COLLATERAL_RECONCILE_SUBMITTED}.
 */
public record LendingOperatorActionEvent(
        String type, UUID marketId, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType() { return "LENDING_" + type; }
    public String subjectType() { return "LendingMarket"; }
    public UUID subjectId() { return marketId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
