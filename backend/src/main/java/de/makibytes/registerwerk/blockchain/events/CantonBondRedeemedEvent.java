package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CantonBondRedeemedEvent(UUID deploymentId, UUID actorId, Instant maturityDate) implements AuditableEvent {
    public String eventType()   { return "CANTON_BOND_REDEEMED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() { return Map.of("maturityDate", maturityDate.toString()); }
}
