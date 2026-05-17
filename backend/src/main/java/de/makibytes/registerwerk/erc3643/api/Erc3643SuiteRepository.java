package de.makibytes.registerwerk.erc3643.api;

import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
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
