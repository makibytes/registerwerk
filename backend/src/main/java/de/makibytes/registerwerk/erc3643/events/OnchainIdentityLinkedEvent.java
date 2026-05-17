package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record OnchainIdentityLinkedEvent(UUID identityId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "ONCHAIN_IDENTITY_LINKED"; }
    public String subjectType() { return "OnchainIdentity"; }
    public UUID   subjectId()   { return identityId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
