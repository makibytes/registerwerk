package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record ClaimIssuedEvent(UUID claimId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "CLAIM_ISSUED"; }
    public String subjectType() { return "OnchainClaim"; }
    public UUID   subjectId()   { return claimId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
