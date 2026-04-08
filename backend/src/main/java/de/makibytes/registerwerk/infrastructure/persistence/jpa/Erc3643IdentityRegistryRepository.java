package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.Erc3643IdentityRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Erc3643IdentityRegistryRepository extends JpaRepository<Erc3643IdentityRegistry, UUID> {

    /** All currently registered (non-removed) investors for a suite. */
    List<Erc3643IdentityRegistry> findBySuiteIdAndRemovedAtIsNull(UUID suiteId);

    /** All entries (including removed) for a suite — for auditing. */
    List<Erc3643IdentityRegistry> findBySuiteId(UUID suiteId);

    /** Look up the active registry entry for a specific wallet on a suite. */
    Optional<Erc3643IdentityRegistry> findBySuiteIdAndWalletAddressAndRemovedAtIsNull(UUID suiteId, String walletAddress);

    /** All suites a given identity is registered in. */
    List<Erc3643IdentityRegistry> findByOnchainIdentityIdAndRemovedAtIsNull(UUID onchainIdentityId);

    boolean existsBySuiteIdAndWalletAddressAndRemovedAtIsNull(UUID suiteId, String walletAddress);
}
