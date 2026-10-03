package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Every burn the redemption dispatched is final (final receipt + indexed BURN transfer): the asset moved from
 *  REDEMPTION_PENDING to REDEEMED (Wave 0b C7). System-attributed. */
public record AssetRedemptionCompletedEvent(UUID assetId, int burns) implements AuditableEvent {
    public String eventType()   { return "ASSET_REDEMPTION_COMPLETED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return Map.of("burns", burns); }
}
