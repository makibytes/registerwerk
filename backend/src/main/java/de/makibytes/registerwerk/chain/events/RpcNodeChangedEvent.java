package de.makibytes.registerwerk.chain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An RPC node governance change. {@code url}/{@code oldUrl} are secret-redacted (userinfo and query
 * values removed, key-like path segments masked); {@code oldUrl} is set only for {@code UPDATED}.
 * The second approver is recorded through {@link #dualControlApproverId()} (P4C-1).
 */
public record RpcNodeChangedEvent(UUID nodeId, UUID actorId, String actorRole, String operation, UUID chainId,
                                  String url, String oldUrl, UUID approverId, UUID requestId)
        implements AuditableEvent {

    /** Legacy shape without actor / URL evidence. */
    public RpcNodeChangedEvent(UUID nodeId, UUID actorId, String actorRole, String operation, UUID chainId) {
        this(nodeId, actorId, actorRole, operation, chainId, null, null, null, null);
    }

    public String eventType()   { return "RPC_NODE_" + operation; }
    public String subjectType() { return "RpcNode"; }
    public UUID subjectId()     { return nodeId; }

    @Override public UUID dualControlApproverId() { return approverId; }
    @Override public UUID correlationId() { return requestId; }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", chainId.toString());
        payload.put("operation", operation);
        if (url != null) payload.put("url", url);
        if (oldUrl != null) payload.put("oldUrl", oldUrl);
        return payload;
    }
}
