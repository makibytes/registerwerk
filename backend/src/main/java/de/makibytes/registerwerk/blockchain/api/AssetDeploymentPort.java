package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.Chain;

import java.util.UUID;

/**
 * Port that blockchain module uses to query asset deployment data
 * without creating a direct blockchain→asset module dependency.
 * Implemented by asset/internal/AssetDeploymentPortImpl.
 */
public interface AssetDeploymentPort {

    /** Minimal deployment info needed by blockchain admin operations. */
    record DeploymentRef(UUID id, UUID assetId, Chain chain, String network, String contractAddress) {}

    DeploymentRef findById(UUID deploymentId);
}
