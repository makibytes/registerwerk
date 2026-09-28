package de.makibytes.registerwerk.erc3643.api;

import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Port interface for deploying ERC-3643 (T-REX) security token suites.
 * Exposed in {@code erc3643.api} so the {@code asset} module can trigger deployments
 * without importing {@code erc3643.internal}.
 */
public interface Erc3643DeploymentPort {

    /**
     * Deploys (or adopts an earlier attempt's) standard T-REX (ERC-3643) suite of six contracts for
     * the given {@code asset_deployment} row. The result carries a null contract address while the
     * suite tx is still unmined; the row then stays PENDING with the tx hash (T3-19).
     */
    CompletableFuture<TokenDeploymentResult> deployStandard(UUID deploymentId, UUID assetId,
                                                            ChainDescriptor chain, String ownerAddress);

    /**
     * Persists the suite record of a standard ERC-3643 deployment that was confirmed by the
     * confirmation poll rather than by the deploy call itself (receipt wait timed out). Idempotent.
     */
    void recordSuiteForDeployment(UUID deploymentId);

    /**
     * Deploys a Confidential ERC-3643 (Zama fhEVM + T-REX) suite for the given asset.
     * The chain must be an fhEVM-capable network (Fhenix or Inco).
     */
    CompletableFuture<String> deployConfidential(UUID assetId, ChainDescriptor chain, String ownerAddress);
}
