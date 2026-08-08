package de.makibytes.registerwerk.erc3643.api;

import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OnchainIdentity} entities.
 */
public interface OnchainIdentityRepository extends JpaRepository<OnchainIdentity, UUID> {

    /**
     * Finds the ONCHAINID identity for a specific legal entity on a specific chain.
     * At most one identity may exist per entity-chain combination (enforced by unique constraint).
     */
    Optional<OnchainIdentity> findByLegalEntityIdAndChainConfigId(UUID legalEntityId, UUID chainConfigId);

    /**
     * Returns all ONCHAINID identities deployed for a legal entity, across all chains.
     */
    List<OnchainIdentity> findByLegalEntityId(UUID legalEntityId);

    Optional<OnchainIdentity> findByIdAndLegalEntityId(UUID id, UUID legalEntityId);

    /** Pending ONCHAINID deployments awaiting their receipt (placeholder address prefix). */
    List<OnchainIdentity> findByIdentityAddressStartingWithAndDeployedByTxIsNotNull(String addressPrefix);
}
