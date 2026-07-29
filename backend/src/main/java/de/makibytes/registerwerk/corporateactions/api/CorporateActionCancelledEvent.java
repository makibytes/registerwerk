package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when an operator cancels a corporate action — the
 * {@code CANCELLED} status has always been part of the {@link CorporateAction.Status} enum
 * and every settlement/idempotency query already excludes it, but no code ever set it: there
 * was no way to actually cancel a mistakenly-raised or no-longer-applicable action.
 */
public record CorporateActionCancelledEvent(
        UUID corporateActionId, UUID actorId, String actorRole, String reason
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_CANCELLED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("reason", reason != null ? reason : "");
    }
}
