package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetDeploymentInitiatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.asset.api.TokenStandard;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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
    private static final EnumSet<Chain> TRACKING_ONLY_CHAINS = EnumSet.noneOf(Chain.class);
    private static final EnumSet<Chain> NON_EVM_CHAINS =
            EnumSet.of(Chain.SOLANA, Chain.CANTON, Chain.STARKNET, Chain.STELLAR);
    private static final EnumSet<Chain> FHEVM_CHAINS =
            EnumSet.of(Chain.FHENIX, Chain.INCO);

    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenDeploymentPort tokenDeploymentPort;
    private final Erc3643DeploymentPort erc3643DeploymentPort;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;

    public AssetDeploymentService(
            AssetDeploymentRepository assetDeploymentRepository,
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            TokenDeploymentPort tokenDeploymentPort,
            Erc3643DeploymentPort erc3643DeploymentPort,
            BlockchainClientRegistry blockchainClientRegistry,
            EvmContractService evmContractService) {
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.tokenDeploymentPort = tokenDeploymentPort;
        this.erc3643DeploymentPort = erc3643DeploymentPort;
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
        TokenStandard standard = asset.getTokenStandard();
        validateDeploymentSupport(chain, standard);

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(assetId);
        deployment.setChain(chain);
        deployment.setNetwork(network);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        AssetDeployment saved = assetDeploymentRepository.save(deployment);

        ChainDescriptor descriptor = new ChainDescriptor(chain, network);

        // Determine correct deployment service based on token standard
        CompletableFuture<String> txFuture = switch (standard) {
            case ERC3643 -> erc3643DeploymentPort.deployStandard(assetId, descriptor, "owner-placeholder");
            case CONF_ERC3643 -> erc3643DeploymentPort.deployConfidential(assetId, descriptor, "owner-placeholder");
            default -> tokenDeploymentPort.deploy(assetId, standard, chain, network, "owner-placeholder");
        };

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

        eventPublisher.publishEvent(new AssetDeploymentInitiatedEvent(saved.getId(), actorId, null, chain.name() + "/" + network.name()));
        return saved;
    }

    private void validateDeploymentSupport(Chain chain, TokenStandard standard) {
        if (TRACKING_ONLY_CHAINS.contains(chain)) {
            throw new UnsupportedOperationException(
                    chain + " is registered for tracking but issuance is not yet implemented");
        }
        if (isConfidentialStandard(standard) && !FHEVM_CHAINS.contains(chain)) {
            throw new UnsupportedOperationException(
                    "Confidential token deployment is not supported on " + chain
                            + ". Use an fhEVM-compatible chain instead.");
        }
    }

    private boolean isConfidentialStandard(TokenStandard standard) {
        return standard == TokenStandard.CONF_ERC20 || standard == TokenStandard.CONF_ERC3643;
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

        // Non-EVM chains have their own confirmation paths (not via ethGetTransactionReceipt)
        Chain deployChain = deployment.getChain();
        if (NON_EVM_CHAINS.contains(deployChain)) {
            log.debug("syncFromChain: chain {} uses a non-EVM confirmation path", deployChain);
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
