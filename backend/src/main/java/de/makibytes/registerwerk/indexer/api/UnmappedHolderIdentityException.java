package de.makibytes.registerwerk.indexer.api;

import java.util.List;
import java.util.UUID;

/**
 * Expected, pre-write reconciliation failure: finalized balances reference wallets that have no
 * registered investor identity. It is deliberately distinct from database/programming failures
 * so the surrounding compensation transaction can durably record FAILED/quarantine state.
 *
 * <p>Also used for the other pre-write refusals that must persist BLOCKED the same way (T3-17,
 * T3-20): a balance on a wallet whose only register entry is closed, an ERC-3525 value
 * projection that is incomplete, a fungible transfer without an amount.
 */
public class UnmappedHolderIdentityException extends IllegalStateException {

    public UnmappedHolderIdentityException(UUID assetId, List<String> wallets) {
        super("Cannot reconcile asset " + assetId
                + ": finalized transfers contain wallet(s) with no registered holder identity: " + wallets);
    }

    public UnmappedHolderIdentityException(UUID assetId, String reason) {
        super("Cannot reconcile asset " + assetId + ": " + reason);
    }
}
