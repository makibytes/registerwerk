package de.makibytes.registerwerk.kyc.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * An ACTIVE §16 eWpG Sperrvermerk whose on-chain freeze must be (re-)applied without the block
 * itself changing — emitted once per block by {@code HolderBlockFreezeResyncRunner} for blocks
 * whose wallet V10 normalised (their original freeze matched no register row). Same payload
 * keys as {@link HolderBlockCreatedEvent}, so the on-chain sync listener handles both alike.
 */
public record HolderBlockFreezeResyncRequestedEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_FREEZE_RESYNC_REQUESTED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
