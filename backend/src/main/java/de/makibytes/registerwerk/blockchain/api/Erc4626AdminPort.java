package de.makibytes.registerwerk.blockchain.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/** Public port for ERC-4626 sync vault admin operations. Used by asset/web/VaultController. */
public interface Erc4626AdminPort {
    UUID strikeNav(UUID deploymentId, BigDecimal navPerShare, Instant effectiveAt,
                   byte[] reportHash, UUID reportDocId, UUID actorId);
    UUID setDepositCap(UUID deploymentId, BigInteger newCap);
}
