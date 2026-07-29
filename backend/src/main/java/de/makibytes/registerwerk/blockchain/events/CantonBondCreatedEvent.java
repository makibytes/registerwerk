package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * {@code actorRole} reflects who (or what) actually triggered bond creation — the common
 * deployment-triggered case passes {@code actorId=null, actorRole="SYSTEM"} (mirroring
 * {@code CantonTokenOperations.createInstrument}'s existing convention).
 */
public record CantonBondCreatedEvent(UUID deploymentId, UUID actorId, String actorRole, String bondType, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CANTON_BOND_CREATED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
