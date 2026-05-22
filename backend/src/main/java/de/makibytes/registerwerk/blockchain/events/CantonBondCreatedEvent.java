package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record CantonBondCreatedEvent(UUID deploymentId, UUID actorId, String bondType, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CANTON_BOND_CREATED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
