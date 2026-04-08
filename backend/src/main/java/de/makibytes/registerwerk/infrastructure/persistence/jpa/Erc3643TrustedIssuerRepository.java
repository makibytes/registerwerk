package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.Erc3643TrustedIssuer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Erc3643TrustedIssuer} entities.
 */
public interface Erc3643TrustedIssuerRepository extends JpaRepository<Erc3643TrustedIssuer, UUID> {

    /** Returns all trusted issuers (active and removed) for a given suite. */
    List<Erc3643TrustedIssuer> findBySuiteId(UUID suiteId);

    /** Returns only currently active trusted issuers (not yet removed) for a suite. */
    List<Erc3643TrustedIssuer> findBySuiteIdAndRemovedAtIsNull(UUID suiteId);
}
