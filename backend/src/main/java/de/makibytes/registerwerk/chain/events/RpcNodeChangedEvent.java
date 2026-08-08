package de.makibytes.registerwerk.chain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

public record RpcNodeChangedEvent(UUID nodeId, UUID actorId, String actorRole, String operation, UUID chainId)
        implements AuditableEvent {
    public String eventType()   { return "RPC_NODE_" + operation; }
    public String subjectType() { return "RpcNode"; }
    public UUID subjectId()     { return nodeId; }
    public Map<String, Object> payload() {
        return Map.of("chainId", chainId.toString(), "operation", operation);
    }
}
