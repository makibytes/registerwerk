package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Public port for ERC-7540 async vault admin operations. Used by asset/web/VaultController.
 *  Fulfilment always settles at the NAV currently struck on-chain; the executed NAV is recorded
 *  from the fulfilment event once confirmed, never supplied by the caller. */
public interface Erc7540AdminPort {
    UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID fulfillRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    /** Registry force-cancel on a legal basis: moves the request's escrow to {@code toAddress}. */
    UUID forceCancelRequest(UUID deploymentId, BigInteger onChainRequestId, String toAddress, String legalBasis,
                            UUID actorId, String actorRole);
    List<VaultRequest> listRequests(UUID assetId, VaultRequestStatus status);
    /** Requests of the deployment's asset in {@code status}, with the current compliance hold
     *  evaluated for PENDING ones. */
    List<VaultRequestView> listRequestViews(UUID deploymentId, VaultRequestStatus status);
}
