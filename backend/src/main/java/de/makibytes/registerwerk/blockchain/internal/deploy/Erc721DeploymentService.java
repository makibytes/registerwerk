package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles on-chain deployment of ERC-721 (NFT) security token contracts via
 * {@code AssetTokenFactory.deployToken(1, name, symbol, assetId)}.
 */
@Service
public class Erc721DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc721DeploymentService.class);

    private static final BigInteger TOKEN_TYPE_ERC721 = BigInteger.ONE;

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;
    private final EvmFactoryDeploymentSupport factoryDeploymentSupport;

    public Erc721DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetLookupPort assetLookupPort,
                                    EvmFactoryDeploymentSupport factoryDeploymentSupport) {
        this.factoryDeploymentSupport = factoryDeploymentSupport;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
    }

    /**
     * Deploys an EwpgERC721 token contract for {@code assetId} on the target chain.
     *
     * @param assetId      ID of the asset to deploy
     * @param chain        target chain descriptor
     * @param ownerAddress on-chain owner address (logged only; factory controls deployment)
     * @return future resolving to the deployment tx hash and the deployed token address
     */
    public CompletableFuture<TokenDeploymentResult> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-721 contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            EvmSigner signer = evmContractService.signer(chain);

            // Idempotent: adopts our contract if it already sits at predictAddress (T1-17).
            TokenDeploymentResult result = factoryDeploymentSupport.deploy(chain, web3j, signer, factoryAddress,
                    TOKEN_TYPE_ERC721, asset.name(), EvmUtils.tokenSymbol(asset), assetId, null);
            log.info("ERC-721 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, result.contractAddress(), result.txHash());
            return result;
        });
    }
}
