package de.makibytes.registerwerk.erc3643.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cross-module surface onto the T-REX agent mint, so the {@code asset} module can issue tokens for a
 * settled primary subscription (T3-08) without importing {@code erc3643.internal}.
 */
public interface Erc3643MintPort {

    /**
     * Mints {@code amount} whole units to {@code toAddress} on the T-REX suite of the given asset
     * deployment ({@code batchMint} of one entry). Refuses a blocked wallet and a frozen register.
     *
     * @return a blockchain-transaction tracking UUID
     * @throws de.makibytes.registerwerk.shared.EntityNotFoundException if the deployment has no suite
     */
    UUID mint(UUID deploymentId, String toAddress, BigDecimal amount, UUID actorId, String actorRole);
}
