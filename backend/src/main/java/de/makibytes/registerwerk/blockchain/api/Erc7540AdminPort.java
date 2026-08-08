package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Public port for ERC-7540 async vault admin operations. Used by asset/web/VaultController. */
public interface Erc7540AdminPort {
    UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId,
                               BigDecimal navAtFulfill, UUID actorId, String actorRole);
    UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId,
                              BigDecimal navAtFulfill, UUID actorId, String actorRole);
    UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID fulfillRequest(UUID deploymentId, BigInteger onChainRequestId,
                        BigDecimal navAtFulfill, UUID actorId, String actorRole);
    UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    List<VaultRequest> listRequests(UUID assetId, VaultRequestStatus status);
}
