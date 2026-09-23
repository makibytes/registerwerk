package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published when an asset's holder sync becomes BLOCKED (or stays blocked on a different set of
 * wallets): finalized positive balances sit on wallets with no holder row, so the register is not
 * reconciled (T2-18). Only transitions are published, not every scheduler re-run. System-attributed.
 */
public record HolderSyncBlockedEvent(UUID assetId, List<String> unmappedWallets) implements AuditableEvent {

    public String eventType()   { return "HOLDER_SYNC_BLOCKED"; }
    public String subjectType() { return "Asset"; }
    public UUID   subjectId()   { return assetId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("unmappedWallets", String.join(",", unmappedWallets));
    }
}
