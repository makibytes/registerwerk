package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
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
 * Handles on-chain deployment of EwpgERC4626 (tokenized vault, sync) contracts via
 * {@code AssetTokenFactory.deployVault(4, name, symbol, assetId, underlyingAsset)}.
 *
 * <p>The underlying asset address is resolved from {@link AssetVaultState#getUnderlyingAssetId()}
 * which must be set on the {@code AssetVaultState} record before deployment.
 * The underlying asset must already be deployed on the same chain.
 */
@Service
public class Erc4626DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(Erc4626DeploymentService.class);

    private static final BigInteger TOKEN_TYPE_ERC4626 = BigInteger.valueOf(4);

    private static final String VAULT_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("VaultDeployed(bytes32,uint8,address,address)");

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;
    private final AssetVaultStateRepository vaultStateRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;

    public Erc4626DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetLookupPort assetLookupPort,
                                    AssetVaultStateRepository vaultStateRepository,
                                    AssetDeploymentRepository assetDeploymentRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
        this.vaultStateRepository = vaultStateRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
    }

    public CompletableFuture<TokenDeploymentResult> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-4626 (sync vault) contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            AssetVaultState vaultState = vaultStateRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalStateException(
                            "AssetVaultState not found for assetId=" + assetId +
                            ". Set the underlying asset via POST /api/v1/assets/{id}/bond-terms before deploying."));

            String underlyingAddress = resolveUnderlyingAddress(vaultState, chain);

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            EvmSigner signer = evmContractService.signer(chain);

            Function deployVault = new Function(
                    "deployVault",
                    Arrays.asList(
                            new Uint8(TOKEN_TYPE_ERC4626),
                            new Utf8String(asset.name()),
                            new Utf8String(EvmUtils.tokenSymbol(asset)),
                            new Bytes32(Erc20DeploymentService.uuidToBytes32(assetId)),
                            new Address(underlyingAddress)
                    ),
                    Collections.singletonList(new TypeReference<Address>() {})
            );

            TransactionReceipt receipt = evmContractService.send(
                    evmContractService.chainConfigId(chain), web3j, signer, factoryAddress, deployVault);
            String vaultAddress = EvmUtils.extractIndexedAddress(receipt, VAULT_DEPLOYED_TOPIC, 3)
                    .orElseThrow(() -> new RuntimeException(
                            "VaultDeployed event not found in receipt: " + receipt.getTransactionHash()));
            log.info("ERC-4626 vault deployed: assetId={} → vaultAddress={} tx={}",
                    assetId, vaultAddress, receipt.getTransactionHash());

            return new TokenDeploymentResult(receipt.getTransactionHash(), vaultAddress);
        });
    }

    private String resolveUnderlyingAddress(AssetVaultState vaultState, ChainDescriptor chain) {
        if (vaultState.getUnderlyingAssetId() == null) {
            throw new IllegalStateException("underlyingAssetId not set on AssetVaultState — configure it via the vault-setup wizard before deploying.");
        }
        return assetDeploymentRepository.findByAssetId(vaultState.getUnderlyingAssetId()).stream()
                .filter(d -> d.getChain() == chain.chain() && d.getNetwork() == chain.network())
                .filter(d -> d.getContractAddress() != null && !d.getContractAddress().isBlank())
                .findFirst()
                .map(AssetDeployment::getContractAddress)
                .orElseThrow(() -> new IllegalStateException(
                        "Underlying asset " + vaultState.getUnderlyingAssetId() + " has no confirmed deployment on " +
                        chain.chain() + "/" + chain.network() + ". Deploy the underlying asset on the same chain first."));
    }
}
