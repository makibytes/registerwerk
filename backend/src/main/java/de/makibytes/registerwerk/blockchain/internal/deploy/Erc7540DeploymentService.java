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
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
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

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final ContractAddressConfig contractAddressConfig;
    private final AssetLookupPort assetLookupPort;
    private final EvmFactoryDeploymentSupport factoryDeploymentSupport;
    private final AssetVaultStateRepository vaultStateRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;

    public Erc7540DeploymentService(BlockchainClientRegistry blockchainClientRegistry,
                                    EvmContractService evmContractService,
                                    ContractAddressConfig contractAddressConfig,
                                    AssetLookupPort assetLookupPort,
                                    AssetVaultStateRepository vaultStateRepository,
                                    AssetDeploymentRepository assetDeploymentRepository,
                                    EvmFactoryDeploymentSupport factoryDeploymentSupport) {
        this.factoryDeploymentSupport = factoryDeploymentSupport;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.contractAddressConfig = contractAddressConfig;
        this.assetLookupPort = assetLookupPort;
        this.vaultStateRepository = vaultStateRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
    }

    public CompletableFuture<TokenDeploymentResult> deploy(UUID assetId, ChainDescriptor chain, String ownerAddress) {
        log.info("Deploying ERC-7540 (async vault) contract: assetId={}, chain={}", assetId, chain);

        return CompletableFuture.supplyAsync(() -> {
            AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));

            AssetVaultState vaultState = vaultStateRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalStateException(
                            "AssetVaultState not found for assetId=" + assetId +
                            ". Configure vault parameters via POST /api/v1/assets/{id}/vault-state before deploying."));

            String underlyingAddress = resolveUnderlyingAddress(vaultState, chain);

            String chainId = chain.chain().name().toLowerCase() + "-" + chain.network().name().toLowerCase();
            String factoryAddress = contractAddressConfig.requireAssetTokenFactory(chainId);

            Web3j web3j = blockchainClientRegistry.getEvmClient(chain);
            EvmSigner signer = evmContractService.signer(chain);

            // Idempotent: adopts our contract if it already sits at predictAddress (T1-17).
            TokenDeploymentResult result = factoryDeploymentSupport.deploy(chain, web3j, signer, factoryAddress,
                    TOKEN_TYPE_ERC7540, asset.name(), EvmUtils.tokenSymbol(asset), assetId, underlyingAddress);
            log.info("ERC-7540 deployed: assetId={} → vaultAddress={} tx={}",
                    assetId, result.contractAddress(), result.txHash());
            return result;
        });
    }

    private String resolveUnderlyingAddress(AssetVaultState vaultState, ChainDescriptor chain) {
        if (vaultState.getUnderlyingAssetId() == null) {
            throw new IllegalStateException("underlyingAssetId must be set on AssetVaultState before deploying ERC-7540.");
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
