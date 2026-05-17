package de.makibytes.registerwerk.blockchain.internal;

import java.math.BigInteger;
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
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;

/**
 * Handles on-chain deployment of ERC-20 security token contracts via {@code AssetTokenFactory}.
 *
 * <p>Calls {@code AssetTokenFactory.deployToken(uint8 tokenType, string name, string symbol,
 * bytes32 assetId)} and extracts the deployed contract address from the {@code TokenDeployed}
 * event emitted by the factory.
 */
@Service
public class Erc20DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc20DeploymentService.class);

    /** AssetTokenFactory constant: ERC-20 type. */
    private static final BigInteger TOKEN_TYPE_ERC20 = java.math.BigInteger.ZERO;

    /**
     * keccak256("TokenDeployed(bytes32,uint8,address)") — used to find the event in receipt logs.
     */
    private static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetRepository assetRepository;

    public Erc20DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                   EvmContractService evmContractService,
                                   ContractAddressConfig contractAddressConfig,
                                   AssetRepository assetRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetRepository = assetRepository;
    }

    /**
     * Deploys an EwpgERC20 token contract for {@code assetId} on the target chain.
     *
     * <p>Calls {@code AssetTokenFactory.deployToken(0, name, symbol, assetId)} using the
     * registry operator credentials. The factory must be pre-deployed; its address is read
     * from {@code registerwerk.contracts.asset-token-factory.{chain-identifier}}.
     *
     * @param assetId      ID of the asset to deploy (must exist in DB)
     * @param chain        target chain descriptor
     * @param ownerAddress on-chain owner/admin address (used for logging only; factory owns the
     *                     contract and the {@code registerwerk.wallet.private-key} is the deployer)
     * @return future resolving to the deployment transaction hash
     */
    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-20 contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            // Encode assetId UUID as bytes32
            byte[] assetIdBytes = uuidToBytes32(assetId);

            Function deployToken = new Function(
                    "deployToken",
                    Arrays.asList(
                            new Uint8(TOKEN_TYPE_ERC20),
                            new Utf8String(asset.getName()),
                            new Utf8String(asset.getTokenStandard().name()),
                            new Bytes32(assetIdBytes)
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(web3j, creds, factoryAddress, deployToken);

            String tokenAddress = extractTokenAddress(receipt);
            log.info("ERC-20 deployed: assetId={} → tokenAddress={} tx={}",
                    assetId, tokenAddress, receipt.getTransactionHash());

            return receipt.getTransactionHash();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Finds the {@code TokenDeployed} event in the receipt logs and returns the token address.
     */
    private String extractTokenAddress(TransactionReceipt receipt) {
        for (Log logEntry : receipt.getLogs()) {
            if (logEntry.getTopics() != null
                    && !logEntry.getTopics().isEmpty()
                    && TOKEN_DEPLOYED_TOPIC.equalsIgnoreCase(logEntry.getTopics().get(0))) {
                // topics[3] = indexed address (left-padded to 32 bytes)
                if (logEntry.getTopics().size() >= 3) {
                    String paddedAddress = logEntry.getTopics().get(2);
                    return "0x" + paddedAddress.substring(paddedAddress.length() - 40);
                }
            }
        }
        throw new RuntimeException(
                "TokenDeployed event not found in receipt: " + receipt.getTransactionHash());
    }

    static byte[] uuidToBytes32(UUID uuid) {
        byte[] b = new byte[32];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            b[24 + i] = (byte) (lo & 0xFF); lo >>= 8;
        }
        for (int i = 7; i >= 0; i--) {
            b[16 + i] = (byte) (hi & 0xFF); hi >>= 8;
        }
        return b;
    }
}
