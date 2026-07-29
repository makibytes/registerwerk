package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a corporate action is first announced (created). Every current caller is a
 * scheduled job ({@code BondMaturityJob}, {@code CouponPaymentJob}) — there is no operator-
 * initiated creation endpoint yet — so {@code actorId} is always null/{@code actorRole="SYSTEM"}
 * for now; the shape still accepts a real actor for when one exists.
 */
public record CorporateActionAnnouncedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, UUID assetId, CorporateAction.ActionType actionType
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_ANNOUNCED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("assetId", assetId.toString(), "actionType", actionType.name());
    }
}
