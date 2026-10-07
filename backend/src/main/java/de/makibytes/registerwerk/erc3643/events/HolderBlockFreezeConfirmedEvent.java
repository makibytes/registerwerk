package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * The freeze of a §16 eWpG Sperrvermerk is final on-chain for one wallet on one deployment: the transaction is
 * SUCCESS under the chain's finality model (H5). Audit evidence that the register-level block reached the chain;
 * the per-deployment state is in {@code holder_block_freeze}.
 */
public record HolderBlockFreezeConfirmedEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_FREEZE_CONFIRMED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
