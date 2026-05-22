package de.makibytes.registerwerk.erc3643;

import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;

import java.util.Optional;
import java.util.UUID;

/** Public API for ERC-3643 (T-REX) suite queries. */
public interface Erc3643Api {

    Optional<Erc3643Suite> findSuiteByDeployment(UUID deploymentId);
}
