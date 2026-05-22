package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetCreatedEvent(UUID assetId, UUID actorId, String actorRole, String assetNumber, String name) implements AuditableEvent {
    public String eventType()   { return "ASSET_CREATED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() { return Map.of("assetNumber", assetNumber, "name", name); }
}
