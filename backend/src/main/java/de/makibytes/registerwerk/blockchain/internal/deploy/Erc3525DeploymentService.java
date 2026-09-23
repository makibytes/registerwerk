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
 * Handles on-chain deployment of EwpgERC3525 (semi-fungible) contracts via
 * {@code AssetTokenFactory.deployToken(3, name, symbol, assetId)}.
 *
 * <p>ERC-3525 is the primary on-chain representation for bonds and fund tranches:
 * each slot maps to a bond series; each token holds a fungible value (notional).
 */
@Service
public class Erc3525DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc3525DeploymentService.class);

    private static final BigInteger TOKEN_TYPE_ERC3525 = BigInteger.valueOf(3);

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;
    private final EvmFactoryDeploymentSupport factoryDeploymentSupport;

    public Erc3525DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
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

    public CompletableFuture<TokenDeploymentResult> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-3525 (SFT) contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            EvmSigner signer = evmContractService.signer(chain);

            // Idempotent: adopts our contract if it already sits at predictAddress (T1-17).
            TokenDeploymentResult result = factoryDeploymentSupport.deploy(chain, web3j, signer, factoryAddress,
                    TOKEN_TYPE_ERC3525, asset.name(), EvmUtils.tokenSymbol(asset), assetId, null);
            log.info("ERC-3525 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, result.contractAddress(), result.txHash());
            return result;
        });
    }
}
