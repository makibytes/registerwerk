package de.makibytes.registerwerk.indexer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Published whenever {@code HolderDataService.syncHoldersFromBlockchain} creates a new
 * {@code AssetHolder} row or changes an existing one's nominal amount from indexed on-chain
 * transfer history. Previously this reconciliation pass updated the
 * register directly with no audit trail and — since it never published
 * {@code asset.events.HolderEnteredEvent}/{@code HolderRegisterChangedEvent} — never triggered
 * the §19(2) statement obligation either: a holder whose position changed purely through
 * on-chain activity (not an operator/issuer action) silently never received their statutory
 * INITIAL_ENTRY/CHANGE Registerauszug.
 *
 * <p>Deliberately its own event rather than directly publishing {@code asset.events}'
 * {@code HolderEnteredEvent}/{@code HolderRegisterChangedEvent} from here: {@code asset.web}
 * already depends on {@code indexer.IndexerApi}, so the reverse dependency would create a
 * Spring Modulith module cycle. {@code registerstatement}'s listener translates this into the
 * same statement-issuance trigger instead.
 */
public record HolderBalanceSyncedEvent(
        UUID holderId, UUID assetId, boolean newlyCreated, BigDecimal nominalAmount)
        implements AuditableEvent {

    public String eventType()   { return newlyCreated ? "HOLDER_BALANCE_SYNC_CREATED" : "HOLDER_BALANCE_SYNC_UPDATED"; }
    public String subjectType() { return "AssetHolder"; }
    public UUID   subjectId()   { return holderId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of("assetId", assetId.toString(), "nominalAmount", nominalAmount != null ? nominalAmount.toPlainString() : "0");
    }
}
