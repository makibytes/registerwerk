package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
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
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
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

    private static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetRepository assetRepository;

    public Erc3525DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetRepository assetRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetRepository = assetRepository;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-3525 (SFT) contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            Function deployToken = new Function(
                    "deployToken",
                    Arrays.asList(
                            new Uint8(TOKEN_TYPE_ERC3525),
                            new Utf8String(asset.getName()),
                            new Utf8String(asset.getTokenStandard().name()),
                            new Bytes32(Erc20DeploymentService.uuidToBytes32(assetId))
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, creds, factoryAddress, deployToken);
            String tokenAddress = extractTokenAddress(receipt);
            log.info("ERC-3525 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }

    private String extractTokenAddress(TransactionReceipt receipt) {
        for (Log logEntry : receipt.getLogs()) {
            if (logEntry.getTopics() != null && !logEntry.getTopics().isEmpty()
                    && TOKEN_DEPLOYED_TOPIC.equalsIgnoreCase(logEntry.getTopics().get(0))
                    && logEntry.getTopics().size() >= 3) {
                String padded = logEntry.getTopics().get(2);
                return "0x" + padded.substring(padded.length() - 40);
            }
        }
        throw new RuntimeException("TokenDeployed event not found in receipt: " + receipt.getTransactionHash());
    }
}
