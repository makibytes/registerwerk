package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.Erc3643Suite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Erc3643Suite} entities.
 */
public interface Erc3643SuiteRepository extends JpaRepository<Erc3643Suite, UUID> {

    /**
     * Finds the T-REX suite associated with a specific asset deployment.
     * Each asset deployment has at most one suite (enforced by unique constraint).
     */
    Optional<Erc3643Suite> findByAssetDeploymentId(UUID assetDeploymentId);
}
