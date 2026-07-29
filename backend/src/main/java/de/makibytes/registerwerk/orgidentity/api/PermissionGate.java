package de.makibytes.registerwerk.orgidentity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API façade for org-identity checks, consumed by other modules
 * (marketplace, kyc, trading) without crossing into orgidentity/internal/.
 *
 * <p>All checks are fail-closed: a wallet with a pending binding, a suspended
 * org, or an unknown chain never passes.
 */
public interface PermissionGate {

    /**
     * Returns true if the wallet has a confirmed (ACTIVE) binding to the given
     * legal entity's active organization on the given chain.
     */
    boolean isWalletBoundToEntity(String walletAddress, UUID legalEntityId, UUID chainConfigId);

    /** Returns true if the entity's org registration on the chain is confirmed and not suspended. */
    boolean isOrgActive(UUID legalEntityId, UUID chainConfigId);

    /** Resolves the legal entity whose active org the wallet is bound to, if any. */
    Optional<UUID> entityForWallet(String walletAddress, UUID chainConfigId);
}
