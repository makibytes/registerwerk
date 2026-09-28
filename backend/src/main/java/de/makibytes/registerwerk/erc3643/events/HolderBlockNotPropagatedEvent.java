package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * An ACTIVE §16 eWpG Sperrvermerk resolved to no deployment to freeze although its asset has EVM
 * deployments (T3-15): the block is in the register but not on-chain, so on-chain paths that rely
 * on the frozen flag (repo repay/liquidate, direct transfers) stay open. Operators must apply the
 * freeze manually. Published by {@code SperrvermerkOnchainSyncListener}.
 */
public record HolderBlockNotPropagatedEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_NOT_PROPAGATED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
