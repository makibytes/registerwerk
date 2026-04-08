package de.makibytes.registerwerk.application.asset;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.blockchain.*;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the lifecycle of on-chain asset deployments.
 */
@Service
@Transactional
public class AssetDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(AssetDeploymentService.class);

    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetRepository assetRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final Erc20DeploymentService erc20DeploymentService;
    private final Erc721DeploymentService erc721DeploymentService;
    private final Erc1155DeploymentService erc1155DeploymentService;
    private final Erc3643DeploymentService erc3643DeploymentService;
    private final ConfidentialErc20Service confidentialErc20Service;
    private final ConfidentialErc3643Service confidentialErc3643Service;
    private final SolanaTokenService solanaTokenService;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;

    public AssetDeploymentService(
            AssetDeploymentRepository assetDeploymentRepository,
            AssetRepository assetRepository,
            AuditEventPublisher auditEventPublisher,
            Erc20DeploymentService erc20DeploymentService,
            Erc721DeploymentService erc721DeploymentService,
            Erc1155DeploymentService erc1155DeploymentService,
            Erc3643DeploymentService erc3643DeploymentService,
            ConfidentialErc20Service confidentialErc20Service,
            ConfidentialErc3643Service confidentialErc3643Service,
            SolanaTokenService solanaTokenService,
            BlockchainClientRegistry blockchainClientRegistry,
            EvmContractService evmContractService) {
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository = assetRepository;
        this.auditEventPublisher = auditEventPublisher;
        this.erc20DeploymentService = erc20DeploymentService;
        this.erc721DeploymentService = erc721DeploymentService;
        this.erc1155DeploymentService = erc1155DeploymentService;
        this.erc3643DeploymentService = erc3643DeploymentService;
        this.confidentialErc20Service = confidentialErc20Service;
        this.confidentialErc3643Service = confidentialErc3643Service;
        this.solanaTokenService = solanaTokenService;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
    }

    /**
     * Creates a PENDING deployment record and delegates contract deployment to the
     * appropriate token-standard-specific service.
     */
    public AssetDeployment deploy(UUID assetId, Chain chain, Network network, UUID actorId) {
        Asset asset = assetRepository.findById(assetId)
            .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(assetId);
        deployment.setChain(chain);
        deployment.setNetwork(network);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        AssetDeployment saved = assetDeploymentRepository.save(deployment);

        ChainDescriptor descriptor = new ChainDescriptor(chain, network);

        // Determine correct deployment service based on token standard
        CompletableFuture<String> txFuture;
        TokenStandard standard = asset.getTokenStandard();

        if (chain == Chain.SOLANA) {
            txFuture = solanaTokenService.createSplToken(assetId, network, "owner-placeholder");
        } else {
            txFuture = switch (standard) {
                case ERC20 -> erc20DeploymentService.deploy(assetId, descriptor, "owner-placeholder");
                case ERC721 -> erc721DeploymentService.deploy(assetId, descriptor, "owner-placeholder");
                case ERC1155 -> erc1155DeploymentService.deploy(assetId, descriptor, "owner-placeholder");
                case ERC3643 -> erc3643DeploymentService.deploy(assetId, descriptor, "owner-placeholder");
                case CONF_ERC20 -> confidentialErc20Service.deploy(assetId, descriptor, "owner-placeholder");
                case CONF_ERC3643 -> confidentialErc3643Service.deploy(assetId, descriptor, "owner-placeholder");
                default -> {
                    log.warn("No deployment service for token standard: {}", standard);
                    yield CompletableFuture.completedFuture("UNSUPPORTED_" + standard);
                }
            };
        }

        UUID deploymentId = saved.getId();
        txFuture.whenComplete((txHash, ex) -> {
            if (ex != null) {
                log.error("Deployment failed for deploymentId={}", deploymentId, ex);
                assetDeploymentRepository.findById(deploymentId).ifPresent(dep -> {
                    dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
                    assetDeploymentRepository.save(dep);
                });
            } else {
                log.info("Deployment tx sent: deploymentId={}, txHash={}", deploymentId, txHash);
                assetDeploymentRepository.findById(deploymentId).ifPresent(dep -> {
                    dep.setDeployedByTx(txHash);
                    assetDeploymentRepository.save(dep);
                });
            }
        });

        auditEventPublisher.publish("ASSET_DEPLOYMENT_INITIATED", "AssetDeployment", saved.getId(),
            actorId, null, Map.of("chain", chain.name(), "network", network.name()));
        return saved;
    }

    @Transactional(readOnly = true)
    public AssetDeployment getDeployment(UUID deploymentId) {
        return assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    @Transactional(readOnly = true)
    public List<AssetDeployment> listDeployments(UUID assetId) {
        return assetDeploymentRepository.findByAssetId(assetId);
    }

    /**
     * Marks a deployment as CONFIRMED with the given contract address and tx hash.
     */
    public void confirmDeployment(UUID deploymentId, String contractAddress, String txHash) {
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        deployment.setContractAddress(contractAddress);
        deployment.setDeployedByTx(txHash);
        deployment.setDeployedAt(Instant.now());
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        assetDeploymentRepository.save(deployment);
        log.info("Confirmed deployment: id={}, contract={}", deploymentId, contractAddress);
    }

    /**
     * Queries the chain for the deployment transaction receipt and, if confirmed,
     * marks the deployment as CONFIRMED with the mined contract address.
     *
     * <p>No-ops if the deployment has no tx hash or is already CONFIRMED / FAILED.
     *
     * @param deploymentId ID of the deployment to sync
     */
    public void syncFromChain(UUID deploymentId) {
        AssetDeployment deployment = getDeployment(deploymentId);
        String txHash = deployment.getDeployedByTx();

        if (txHash == null || txHash.isBlank() || txHash.startsWith("0x-PENDING")) {
            log.debug("syncFromChain: no on-chain tx for deploymentId={}", deploymentId);
            return;
        }

        AssetDeployment.DeploymentStatus status = deployment.getDeploymentStatus();
        if (status == AssetDeployment.DeploymentStatus.CONFIRMED
                || status == AssetDeployment.DeploymentStatus.FAILED) {
            log.debug("syncFromChain: deploymentId={} already in terminal state {}", deploymentId, status);
            return;
        }

        if (deployment.getChain() == null || deployment.getNetwork() == null) {
            log.warn("syncFromChain: deploymentId={} has no chain/network set", deploymentId);
            return;
        }

        try {
            ChainDescriptor descriptor = new ChainDescriptor(deployment.getChain(), deployment.getNetwork());
            Web3j web3j = blockchainClientRegistry.getEvmClient(descriptor);

            Optional<TransactionReceipt> receiptOpt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();

            if (receiptOpt.isEmpty()) {
                log.info("syncFromChain: tx={} not yet mined for deploymentId={}", txHash, deploymentId);
                return;
            }

            TransactionReceipt receipt = receiptOpt.get();
            String contractAddress = receipt.getContractAddress();

            if (contractAddress != null && !contractAddress.isBlank()) {
                deployment.setContractAddress(contractAddress);
                deployment.setDeployedAt(Instant.now());
                deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
                assetDeploymentRepository.save(deployment);
                log.info("syncFromChain: confirmed deploymentId={} contractAddress={}", deploymentId, contractAddress);
            } else {
                // Tx mined but no contract address → reverted or non-deployment tx
                deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
                assetDeploymentRepository.save(deployment);
                log.warn("syncFromChain: tx={} mined but no contractAddress; marking FAILED", txHash);
            }
        } catch (Exception e) {
            log.error("syncFromChain: error fetching receipt for deploymentId={}: {}",
                    deploymentId, e.getMessage(), e);
        }
    }
}
