package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;

import java.util.Map;
import java.util.UUID;

record CorporateActionSettlementRequestedEvent(
        UUID corporateActionId,
        UUID assetId,
        CorporateAction.ActionType actionType
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_SETTLEMENT_REQUESTED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() {
        return Map.of("assetId", assetId.toString(), "actionType", actionType.name());
    }
}
