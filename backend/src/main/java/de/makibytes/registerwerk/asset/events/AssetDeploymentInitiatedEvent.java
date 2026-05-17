package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record AssetDeploymentInitiatedEvent(UUID assetId, UUID actorId, String actorRole, String chain) implements AuditableEvent {
    public String eventType()   { return "ASSET_DEPLOYMENT_INITIATED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public Map<String, Object> payload() { return chain != null ? Map.of("chain", chain) : Map.of(); }
}
