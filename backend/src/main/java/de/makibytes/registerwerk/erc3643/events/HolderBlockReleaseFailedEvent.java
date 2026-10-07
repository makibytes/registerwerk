package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * The on-chain unfreeze that follows a lifted §16 eWpG Sperrvermerk failed (could not be submitted, reverted or
 * was replaced). The wallet stays frozen on-chain although the register no longer blocks it - the safe direction,
 * but the holder cannot move assets, so an operator must retry or repair it (H5).
 */
public record HolderBlockReleaseFailedEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_RELEASE_FAILED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
