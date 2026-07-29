package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/** §16 eWpG Sperrvermerk lifted (manually, with 4-eyes approval, or auto-expired). */
public record HolderBlockLiftedEvent(
        UUID holderBlockId, UUID actorId, String actorRole, UUID dualControlApproverId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_LIFTED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
