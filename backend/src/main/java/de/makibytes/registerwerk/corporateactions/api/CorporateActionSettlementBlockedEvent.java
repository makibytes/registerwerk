package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when the automated settlement path deliberately declines to dispatch a corporate
 * action and leaves it in {@code AWAITING_SETTLEMENT} for the operator, with the reason also
 * written to the action's operator-visible {@code notes}. System-attributed.
 */
public record CorporateActionSettlementBlockedEvent(UUID corporateActionId, String reason)
        implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_SETTLEMENT_BLOCKED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() { return Map.of("reason", reason); }
}
