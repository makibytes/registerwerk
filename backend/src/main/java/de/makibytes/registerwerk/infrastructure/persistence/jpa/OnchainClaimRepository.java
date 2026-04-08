package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.OnchainClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OnchainClaim} entities.
 */
public interface OnchainClaimRepository extends JpaRepository<OnchainClaim, UUID> {

    /**
     * Returns all claims issued to a specific ONCHAINID identity.
     */
    List<OnchainClaim> findByOnchainIdentityId(UUID onchainIdentityId);

    /**
     * Returns all claims of a specific topic issued to a specific ONCHAINID identity.
     * Useful for checking whether a required claim topic is satisfied.
     */
    List<OnchainClaim> findByOnchainIdentityIdAndTopic(UUID onchainIdentityId, long topic);
}
