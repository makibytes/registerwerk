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
     * Returns true if either the entity or the wallet address has an ACTIVE holder block
     * (fail closed — callers must not execute the operation when this returns true).
     *
     * @param entityId      the legal entity involved, or {@code null} if not resolvable in
     *                      this context (the wallet-address check still applies)
     * @param walletAddress the wallet address involved, or {@code null} if not applicable
     */
    boolean isBlocked(UUID entityId, String walletAddress);
}
