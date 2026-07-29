package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** §16 eWpG Sperrvermerk created (court order, insolvency, pledge, etc.). */
public record HolderBlockCreatedEvent(
        UUID holderBlockId, UUID actorId, String actorRole, UUID dualControlApproverId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_CREATED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
