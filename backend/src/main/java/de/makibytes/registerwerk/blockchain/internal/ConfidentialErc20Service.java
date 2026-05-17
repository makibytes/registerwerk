package de.makibytes.registerwerk.blockchain.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;

/**
 * Deploys ERC-7984 confidential fungible tokens (Zama fhEVM) via
 * {@code EwpgConfidentialFactory.deployConfidentialErc20(bytes32, string, string)}.
 *
 * <p>Confidential tokens encrypt balances and transfer amounts with Fully
 * Homomorphic Encryption. The factory must be pre-deployed on an fhEVM-capable
 * chain (Fhenix or Inco) and its address stored in
 * {@code registerwerk.contracts.confidential-factory.{chain-identifier}}.
 */
@Service
public class ConfidentialErc20Service {

    private static final Logger log = LoggerFactory.getLogger(ConfidentialErc20Service.class);

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetRepository assetRepository;

    public ConfidentialErc20Service(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetRepository assetRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetRepository = assetRepository;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying Confidential ERC-20 (ERC-7984): assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireConfidentialFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            byte[] assetIdBytes = Erc20DeploymentService.uuidToBytes32(assetId);

            Function deploy = new Function(
                    "deployConfidentialErc20",
                    Arrays.asList(
                            new Bytes32(assetIdBytes),
                            new Utf8String(asset.getName()),
                            new Utf8String(asset.getTokenStandard().name())
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, creds, factoryAddress, deploy);

            String tokenAddress = ConfidentialTokenEvents.extractTokenAddress(receipt);
            log.info("Confidential ERC-20 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }
}
