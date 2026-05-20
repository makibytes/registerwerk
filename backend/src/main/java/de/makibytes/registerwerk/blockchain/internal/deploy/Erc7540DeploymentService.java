package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetVaultState;
import de.makibytes.registerwerk.asset.api.AssetVaultStateRepository;
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
 * Handles on-chain deployment of EwpgERC7540 (async tokenized vault) contracts via
 * {@code AssetTokenFactory.deployVault(5, name, symbol, assetId, underlyingAsset)}.
 *
 * <p>ERC-7540 is the institutional vault standard that decouples deposit/redeem intent
 * from settlement, enabling T+1 NAV cutoff and off-chain custodian workflows.
 */
@Service
public class Erc7540DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc7540DeploymentService.class);

    private static final BigInteger TOKEN_TYPE_ERC7540 = BigInteger.valueOf(5);

    private static final String VAULT_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("VaultDeployed(bytes32,uint8,address,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetRepository assetRepository;
    private final AssetVaultStateRepository vaultStateRepository;

    public Erc7540DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetRepository assetRepository,
                                    AssetVaultStateRepository vaultStateRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetRepository = assetRepository;
        this.vaultStateRepository = vaultStateRepository;
    }

    public CompletableFuture<String> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-7540 (async vault) contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            AssetVaultState vaultState = vaultStateRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalStateException(
                            "AssetVaultState not found for assetId=" + assetId +
                            ". Configure vault parameters via POST /api/v1/assets/{id}/vault-state before deploying."));

            if (vaultState.getUnderlyingAssetId() == null) {
                throw new IllegalStateException("underlyingAssetId must be set on AssetVaultState before deploying ERC-7540.");
            }

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            Credentials creds = evmContractService.credentials(chain);

            // Underlying address must be pre-resolved by the caller (stored on AssetVaultState via an admin endpoint)
            throw new UnsupportedOperationException(
                    "ERC-7540 deployment requires the underlying asset's on-chain address to be resolved from " +
                    "AssetDeployment. Use the vault-setup wizard which populates this before calling deploy.");
        });
    }

    private String extractVaultAddress(TransactionReceipt receipt) {
        for (Log logEntry : receipt.getLogs()) {
            if (logEntry.getTopics() != null && !logEntry.getTopics().isEmpty()
                    && VAULT_DEPLOYED_TOPIC.equalsIgnoreCase(logEntry.getTopics().get(0))
                    && logEntry.getTopics().size() >= 3) {
                String padded = logEntry.getTopics().get(2);
                return "0x" + padded.substring(padded.length() - 40);
            }
        }
        throw new RuntimeException("VaultDeployed event not found in receipt: " + receipt.getTransactionHash());
    }
}
