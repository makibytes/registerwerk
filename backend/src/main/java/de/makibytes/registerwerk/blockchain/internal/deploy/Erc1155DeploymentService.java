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
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles on-chain deployment of ERC-1155 (multi-token) security token contracts via
 * {@code AssetTokenFactory.deployToken(2, "", symbol, assetId)}.
 *
 * <p>Note: ERC-1155 constructor ignores the {@code name} argument; an empty string is passed.
 */
@Service
public class Erc1155DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc1155DeploymentService.class);

    private static final BigInteger TOKEN_TYPE_ERC1155 = BigInteger.valueOf(2);

    private static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;

    public Erc1155DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                     EvmContractService evmContractService,
                                     ContractAddressConfig contractAddressConfig,
                                     AssetLookupPort assetLookupPort) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
    }

    /**
     * Deploys an EwpgERC1155 token contract for {@code assetId} on the target chain.
     *
     * @param assetId      ID of the asset to deploy
     * @param chain        target chain descriptor
     * @param ownerAddress on-chain owner address (logged only)
     * @return future resolving to the deployment tx hash and the deployed token address
     */
    public CompletableFuture<TokenDeploymentResult> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-1155 contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            Function deployToken = new Function(
                    "deployToken",
                    Arrays.asList(
                            new Uint8(TOKEN_TYPE_ERC1155),
                            new Utf8String(""),                                                   // name ignored by ERC-1155
                            new Utf8String(EvmUtils.tokenSymbol(asset)),
                            new Bytes32(Erc20DeploymentService.uuidToBytes32(assetId))
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, creds, factoryAddress, deployToken);
            String tokenAddress = EvmUtils.extractIndexedAddress(receipt, TOKEN_DEPLOYED_TOPIC, 3)
                    .orElseThrow(() -> new RuntimeException(
                            "TokenDeployed event not found in receipt: " + receipt.getTransactionHash()));
            log.info("ERC-1155 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return new TokenDeploymentResult(receipt.getTransactionHash(), tokenAddress);
        });
    }
}
