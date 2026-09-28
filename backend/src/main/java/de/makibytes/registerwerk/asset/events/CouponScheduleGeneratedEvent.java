package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A bond's coupon schedule was (re)generated from its terms.
 *
 * @param trigger     what caused it: BOND_TERMS, ASSET_ISSUED, TERMS_AMENDMENT or BACKFILL
 * @param rowsCreated future SCHEDULED rows written in this version
 * @param rowsReplaced future SCHEDULED rows (no corporate action yet) deleted and regenerated
 */
public record CouponScheduleGeneratedEvent(UUID assetId, UUID actorId, String actorRole,
                                           int scheduleVersion, int rowsCreated, int rowsReplaced,
                                           String trigger) implements AuditableEvent {
    public String eventType()   { return "COUPON_SCHEDULE_GENERATED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() {
        return Map.of("scheduleVersion", scheduleVersion, "rowsCreated", rowsCreated,
                "rowsReplaced", rowsReplaced, "trigger", trigger);
    }
}
