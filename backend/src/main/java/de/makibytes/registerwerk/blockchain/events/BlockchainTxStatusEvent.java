package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record BlockchainTxStatusEvent(UUID deploymentId, UUID actorId, String actorRole, String status, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "BLOCKCHAIN_TX_" + status; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
