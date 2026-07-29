package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record DeploymentFailedEvent(
        UUID deploymentId, UUID actorId, String actorRole, String reason)
        implements AuditableEvent {
    public String eventType()   { return "ASSET_DEPLOYMENT_FAILED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public Map<String, Object> payload() {
        return reason != null ? Map.of("reason", reason) : Map.of();
    }
}
