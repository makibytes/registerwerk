package de.makibytes.registerwerk.chain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ChainUpdatedEvent(UUID chainConfigId, UUID actorId, String actorRole) implements AuditableEvent {
    public String eventType()   { return "CHAIN_UPDATED"; }
    public String subjectType() { return "ChainConfig"; }
    public UUID   subjectId()   { return chainConfigId; }
    public Map<String, Object> payload() { return Map.of(); }
}
