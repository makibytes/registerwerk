package de.makibytes.registerwerk.deployment.api;

/**
 * What an {@link AssetHolder} row represents (T2-18).
 */
public enum HolderKind {
    /** A registered investor's own wallet — the default for every register entry. */
    INVESTOR,
    /**
     * A pool contract (lending market, DvP escrow, desk inventory, facility) holding units on
     * behalf of others, entered in the operator's name so the chain-derived register reconciles.
     * Entitlements snapshotted for it are held (look-through undecided, PARK-T2-18), not paid.
     */
    NOMINEE_POOL
}
