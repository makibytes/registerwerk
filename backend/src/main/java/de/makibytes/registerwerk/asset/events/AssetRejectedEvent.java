package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetRejectedEvent(UUID assetId, UUID actorId, String actorRole, String reason) implements AuditableEvent {
    public String eventType()   { return "ASSET_REJECTED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() { return reason != null ? Map.of("reason", reason) : Map.of(); }
}
