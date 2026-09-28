package de.makibytes.registerwerk.deployment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port through which the indexer's holder sync persists an asset's register-reconciliation state
 * (T2-18) without importing the Asset entity — {@code asset} already depends on {@code indexer},
 * so the reverse import would be a module cycle. Implemented by
 * {@code asset/internal/HolderSyncStatusPortImpl}.
 *
 * <p>Both methods report whether the call changed the persisted state, so the caller audits
 * transitions only — the scheduler re-runs every few minutes and must not write an audit row per
 * run while an asset stays blocked.
 */
public interface HolderSyncStatusPort {

    /**
     * Records a successful reconciliation at {@code at} (status OK, blocked reason/wallets cleared,
     * {@code last_successful_holder_sync_at = at}).
     *
     * @return true when the asset was BLOCKED before this call
     */
    boolean markReconciled(UUID assetId, Instant at);

    /**
     * Records a refused reconciliation: status BLOCKED with the unmapped wallets. The last
     * successful sync timestamp is left untouched — it is what the banner and the corporate-action
     * freshness gate compare against.
     *
     * @return true when the asset was not BLOCKED before, or was blocked on a different wallet set
     */
    boolean markBlocked(UUID assetId, Instant at, List<String> unmappedWallets, String reason);

    /**
     * T3-09: records how many active, non-chain-derived register entries with a positive nominal
     * the latest sync found on this deployed asset (entries the chain does not back). Reported on
     * the operator banner and the {@code registerwerk_register_offchain_rows_on_deployed_asset}
     * gauge; deliberately not a BLOCKED reason.
     */
    void recordOffchainRows(UUID assetId, int count);
}
