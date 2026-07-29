package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a second REGISTRY_ADMIN approves a computed corporate action for
 * settlement (dual control / Vieraugenprinzip). The {@code corporate_action} table has
 * carried {@code dual_control_approver_id}/{@code dual_control_approved_at} columns since
 * inception, but nothing populated them and the scheduled settlement job never checked
 * them — a corporate action (coupon, dividend, split, redemption, ...) could move real
 * money/tokens on a single actor's say-so despite the schema implying a compliance
 * control existed. {@link de.makibytes.registerwerk.corporateactions.internal.CorporateActionService}
 * now refuses to settle a corporate action until this event's approval has been recorded.
 */
public record CorporateActionDualControlApprovedEvent(
        UUID corporateActionId, UUID actorId, String actorRole, UUID initiatedBy
) implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_DUAL_CONTROL_APPROVED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public Map<String, Object> payload() {
        return Map.of("initiatedBy", initiatedBy != null ? initiatedBy.toString() : "");
    }
}
