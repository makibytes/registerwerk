package de.makibytes.registerwerk.erc3643.api;

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
     * Deploys a standard T-REX (ERC-3643) suite of six contracts for the given asset.
     */
    CompletableFuture<String> deployStandard(UUID assetId, ChainDescriptor chain, String ownerAddress);

    /**
     * Deploys a Confidential ERC-3643 (Zama fhEVM + T-REX) suite for the given asset.
     * The chain must be an fhEVM-capable network (Fhenix or Inco).
     */
    CompletableFuture<String> deployConfidential(UUID assetId, ChainDescriptor chain, String ownerAddress);
}
