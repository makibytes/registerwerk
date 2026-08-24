package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an issuer proposes a new corporate action (DIVIDEND/SPLIT/CALL) —
 * {@code CorporateActionService.propose}. Unlike {@link CorporateActionAnnouncedEvent}, this
 * carries a real, non-nil {@code actorId}: the first time an actual human (not a scheduled job)
 * is recorded as having created a corporate action in this system's history.
 */
public record CorporateActionProposedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, UUID assetId, CorporateAction.ActionType actionType
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_PROPOSED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("assetId", assetId.toString(), "actionType", actionType.name());
    }
}
