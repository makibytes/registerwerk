package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.VaultRequest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Public port for ERC-7540 async vault admin operations. Used by asset/web/VaultController. */
public interface Erc7540AdminPort {
    UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId,
                               BigDecimal navAtFulfill, UUID actorId);
    UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId,
                              BigDecimal navAtFulfill, UUID actorId);
    UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId);
    UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId);
    List<VaultRequest> listPendingRequests(UUID assetId);
}
