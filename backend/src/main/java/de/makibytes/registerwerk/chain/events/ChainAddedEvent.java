package de.makibytes.registerwerk.chain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ChainAddedEvent(UUID chainConfigId, UUID actorId, String actorRole, String chainName) implements AuditableEvent {
    public String eventType()   { return "CHAIN_ADDED"; }
    public String subjectType() { return "ChainConfig"; }
    public UUID   subjectId()   { return chainConfigId; }
    public Map<String, Object> payload() { return chainName != null ? Map.of("chainName", chainName) : Map.of(); }
}
