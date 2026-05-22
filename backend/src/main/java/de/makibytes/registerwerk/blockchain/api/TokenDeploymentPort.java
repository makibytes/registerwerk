package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Port interface for deploying tokenized securities on-chain.
 * Abstracts over all supported chains and token standards except ERC-3643 variants,
 * which are handled by the erc3643 module via {@code Erc3643DeploymentPort}.
 */
public interface TokenDeploymentPort {

    /**
     * Deploys a token contract for the given asset on the specified chain/network.
     *
     * @param assetId      asset to deploy (must exist in DB)
     * @param standard     token standard
     * @param chain        target chain
     * @param network      mainnet or testnet
     * @param ownerAddress on-chain owner address (for Canton: issuer party ID; for EVM: logged only)
     * @return future resolving to the transaction hash / signature / contract ID
     */
    CompletableFuture<String> deploy(
            UUID assetId, TokenStandard standard, Chain chain, Network network, String ownerAddress);
}
