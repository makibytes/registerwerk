package de.makibytes.registerwerk.erc3643.api;

import de.makibytes.registerwerk.erc3643.api.Erc3643ComplianceModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Erc3643ComplianceModule} entities.
 */
public interface Erc3643ComplianceModuleRepository extends JpaRepository<Erc3643ComplianceModule, UUID> {

    /** Returns all compliance modules (active and removed) for a given suite. */
    List<Erc3643ComplianceModule> findBySuiteId(UUID suiteId);

    /** Returns only currently active compliance modules (not yet removed) for a suite. */
    List<Erc3643ComplianceModule> findBySuiteIdAndRemovedAtIsNull(UUID suiteId);
}
