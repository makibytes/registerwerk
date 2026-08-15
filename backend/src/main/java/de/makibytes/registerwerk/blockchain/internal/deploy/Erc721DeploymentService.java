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
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
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

    private static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;

    public Erc721DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetLookupPort assetLookupPort) {
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

            Function deployToken = new Function(
                    "deployToken",
                    Arrays.asList(
                            new Uint8(TOKEN_TYPE_ERC721),
                            new Utf8String(asset.name()),
                            new Utf8String(EvmUtils.tokenSymbol(asset)),
                            new Bytes32(Erc20DeploymentService.uuidToBytes32(assetId))
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, signer, factoryAddress, deployToken);
            String tokenAddress = EvmUtils.extractIndexedAddress(receipt, TOKEN_DEPLOYED_TOPIC, 3)
                    .orElseThrow(() -> new RuntimeException(
                            "TokenDeployed event not found in receipt: " + receipt.getTransactionHash()));
            log.info("ERC-721 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return new TokenDeploymentResult(receipt.getTransactionHash(), tokenAddress);
        });
    }
}
