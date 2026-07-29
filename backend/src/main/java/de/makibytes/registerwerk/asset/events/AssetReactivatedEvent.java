package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired when a SUSPENDED asset is reactivated back to ISSUED — the correction path for a
 * wrongful suspend. Previously assets could be suspended but never resumed (only
 * customer/org entities had a reactivate transition); a mis-clicked suspend was
 * permanent short of a manual DB fix.
 */
public record AssetReactivatedEvent(UUID assetId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "ASSET_REACTIVATED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() { return Map.of(); }
}
