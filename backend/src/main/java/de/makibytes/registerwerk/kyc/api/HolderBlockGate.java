package de.makibytes.registerwerk.kyc.api;

import java.util.UUID;

/**
 * Public API façade for §16 eWpG Sperrvermerk enforcement.
 * Used by {@code blockchain}, {@code erc3643} and {@code trading} to check for an active
 * legal block before executing a transfer, mint, or settlement, without crossing into
 * {@code kyc/internal}.
 */
public interface HolderBlockGate {

    /**
     * Returns true if either the entity or the wallet address has an ACTIVE or EXPIRY_REVIEW holder block
     * (fail closed — callers must not execute the operation when this returns true).
     *
     * @param entityId      the legal entity involved, or {@code null} if not resolvable in
     *                      this context (the wallet-address check still applies)
     * @param walletAddress the wallet address involved, or {@code null} if not applicable
     */
    boolean isBlocked(UUID entityId, String walletAddress);

    /**
     * Entity-level check that also matches wallet-only blocks: true if the entity itself, or any
     * wallet the entity holds units on, is covered by a blocking block (6-25). Use where only the
     * entity is known (repo desk, lending), so a court order naming just a wallet is not invisible.
     */
    boolean isEntityBlocked(UUID entityId);

    /**
     * True if a blocking block still covers {@code walletAddress} for the given asset (a block with
     * no asset covers every asset). Used to decide which on-chain freezes may be released after a lift.
     */
    boolean isBlockedForAsset(String walletAddress, UUID assetId);
}
