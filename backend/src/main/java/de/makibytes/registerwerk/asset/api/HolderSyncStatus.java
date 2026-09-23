package de.makibytes.registerwerk.asset.api;

/**
 * Reconciliation state of an asset's chain-derived register (T2-18). See
 * {@link Asset#getHolderSyncStatus()}.
 */
public enum HolderSyncStatus {
    /** The last holder sync reconciled every finalized balance to a holder row. */
    OK,
    /** Finalized balances sit on wallets with no holder row; the register is stale. */
    BLOCKED
}
