package de.makibytes.registerwerk.erc3643;

import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;

import java.util.Optional;
import java.util.UUID;

/** Public API for ERC-3643 (T-REX) suite queries. */
public interface Erc3643Api {

    Optional<Erc3643Suite> findSuiteByDeployment(UUID deploymentId);

    /**
     * Returns the ONCHAINID identity for a legal entity on a chain, deploying a new
     * identity proxy via the chain's IdFactory if none exists yet. A freshly deployed
     * identity carries a placeholder address until the deploy transaction is confirmed.
     *
     * @param actorId   ID of the user triggering identity creation (for audit); may be
     *                  {@code null} for system-initiated calls
     * @param actorRole role of the triggering user (for audit)
     */
    OnchainIdentity getOrCreateIdentity(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole);

    /** Returns the ONCHAINID identity for a legal entity on a chain, if one exists. */
    Optional<OnchainIdentity> findIdentity(UUID legalEntityId, UUID chainConfigId);

    /**
     * Whether a wallet is registered AND holds all required KYC/AML claims in the given
     * suite's IdentityRegistry (local DB mirror — see {@code IdentityRegistryService.isVerified}).
     * Used as the extra ERC-3643-specific eligibility check for {@code ASSET_TOKEN_ADMIN} grants.
     */
    boolean isWalletVerified(UUID suiteId, String walletAddress);

    /** Chains on which the legal entity has an ONCHAINID record (deployed or pending). */
    java.util.List<UUID> identityChainIds(UUID legalEntityId);

    /**
     * Revokes the legal entity's confirmed KYC (topic 1) and AML (topic 2) claims on one chain:
     * {@code ONCHAINID.removeClaim} plus issuer-level {@code ClaimIssuer.revokeClaimBySignature}.
     * Idempotent; each submitted step emits a {@code ClaimRevokedEvent} carrying {@code auditDetails}.
     *
     * @return number of claims still needing a retry (0 = done on this chain)
     */
    int revokeComplianceClaims(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole,
                               java.util.Map<String, Object> auditDetails);
}
