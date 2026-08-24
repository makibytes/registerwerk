package de.makibytes.registerwerk.indexer.api;

import java.util.List;
import java.util.UUID;

/**
 * Expected, pre-write reconciliation failure: finalized balances reference wallets that have no
 * registered investor identity. It is deliberately distinct from database/programming failures
 * so the surrounding compensation transaction can durably record FAILED/quarantine state.
 */
public class UnmappedHolderIdentityException extends IllegalStateException {

    public UnmappedHolderIdentityException(UUID assetId, List<String> wallets) {
        super("Cannot reconcile asset " + assetId
                + ": finalized transfers contain wallet(s) with no registered holder identity: " + wallets);
    }
}
