package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record DeploymentConfirmedEvent(
        UUID deploymentId, UUID actorId, String actorRole, String contractAddress, String txHash)
        implements AuditableEvent {
    public String eventType()   { return "ASSET_DEPLOYMENT_CONFIRMED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public Map<String, Object> payload() {
        return Map.of("contractAddress", contractAddress == null ? "" : contractAddress,
                "txHash", txHash == null ? "" : txHash);
    }
}
