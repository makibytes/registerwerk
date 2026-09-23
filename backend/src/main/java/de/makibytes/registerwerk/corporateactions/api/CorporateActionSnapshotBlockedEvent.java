package de.makibytes.registerwerk.corporateactions.api;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a corporate action's record-date snapshot is refused because the asset's
 * register is not reconciled (T2-18) and the action moves to {@code SNAPSHOT_BLOCKED}, or is
 * still blocked for a different reason. System-attributed.
 */
public record CorporateActionSnapshotBlockedEvent(UUID corporateActionId, UUID assetId, String reason)
        implements AuditableEvent {
    @Override public String eventType()   { return "CORPORATE_ACTION_SNAPSHOT_BLOCKED"; }
    @Override public String subjectType() { return "CORPORATE_ACTION"; }
    @Override public UUID   subjectId()   { return corporateActionId; }
    @Override public UUID   actorId()     { return null; }
    @Override public String actorRole()   { return "SYSTEM"; }
    @Override public Map<String, Object> payload() { return Map.of("assetId", assetId.toString(), "reason", reason); }
}
