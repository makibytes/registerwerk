package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.OnchainIdentity;
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
}
