package de.makibytes.registerwerk.blockchain.api;

import java.time.Instant;

/**
 * Live (eth_call) forward-pricing state of an ERC-7540 vault (T1-07): the dealing cut-off the registry
 * configured, the next dealing point, and when the latest NAV was struck.
 *
 * @param applicable            the deployment is an ERC-7540 vault (only those have a dealing cut-off)
 * @param available             the on-chain read succeeded; when false every other field is unknown
 * @param configured            {@code dealingCutoffConfigured()} — false means requests settle at the NAV
 *                              struck at execution time (legacy behaviour, late-trading exposure)
 * @param cutoffSecondsOfDay    UTC seconds since midnight of the daily cut-off, null while unconfigured
 * @param periodSeconds         length of the dealing period (86400 = daily), null while unconfigured
 * @param nextDealingPoint      the dealing point a request placed now would receive, null while unconfigured
 * @param navStruckAt           when the latest NAV was struck on-chain (null: never struck / unknown)
 */
public record VaultDealingState(
        boolean applicable,
        boolean available,
        boolean configured,
        Integer cutoffSecondsOfDay,
        Long periodSeconds,
        Instant nextDealingPoint,
        Instant navStruckAt) {

    public static VaultDealingState notApplicable() {
        return new VaultDealingState(false, false, false, null, null, null, null);
    }

    public static VaultDealingState unavailable() {
        return new VaultDealingState(true, false, false, null, null, null, null);
    }
}
