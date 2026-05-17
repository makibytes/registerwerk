package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetSuspendedEvent(UUID assetId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "ASSET_SUSPENDED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() { return Map.of(); }
}
