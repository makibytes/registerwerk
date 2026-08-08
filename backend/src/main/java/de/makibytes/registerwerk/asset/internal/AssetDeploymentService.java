package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetDeploymentInitiatedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
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
import java.util.Set;
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
    /**
     * Chains where Zama's fhEVM coprocessor is (or will be) actually deployed — Ethereum and
     * Base today, per Zama's own "fhEVM Coprocessor: Run FHE smart contracts on Ethereum, Base,
     * and other EVM chains" announcement. Deliberately excludes {@code FHENIX}/{@code INCO} —
     * both real chains but running their OWN, separate, non-Zama FHE stacks with incompatible
     * libraries; Registerwerk's confidential contracts (ConfidentialERC20/ERC3643) are built
     * specifically against Zama's TFHE.sol/Gateway API and will not function against either.
     * Add {@code Chain.CANTON} here once T-REX Chain is represented as its own {@code Chain}
     * value and has published its FHEVM infrastructure addresses (T-REX Network announced in
     * March 2026 that Zama is becoming its confidentiality layer, but T-REX Chain is not yet a
     * distinct entry in this enum).
     */
    private static final EnumSet<Chain> FHEVM_CHAINS =
            EnumSet.of(Chain.ETHEREUM, Chain.BASE);

    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenDeploymentPort tokenDeploymentPort;
    private final Erc3643DeploymentPort erc3643DeploymentPort;
    private final BlockchainClientRegistry blockchainClientRegistry;
    private final EvmContractService evmContractService;
    private final AssetDeploymentCompletionWriter completionWriter;

    public AssetDeploymentService(
            AssetDeploymentRepository assetDeploymentRepository,
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            TokenDeploymentPort tokenDeploymentPort,
            Erc3643DeploymentPort erc3643DeploymentPort,
            BlockchainClientRegistry blockchainClientRegistry,
            EvmContractService evmContractService,
            AssetDeploymentCompletionWriter completionWriter) {
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.tokenDeploymentPort = tokenDeploymentPort;
        this.erc3643DeploymentPort = erc3643DeploymentPort;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.completionWriter = completionWriter;
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
        CompletableFuture<TokenDeploymentResult> txFuture = switch (standard) {
            case ERC3643 -> erc3643DeploymentPort.deployStandard(assetId, descriptor, "owner-placeholder");
            case CONF_ERC3643 -> erc3643DeploymentPort.deployConfidential(assetId, descriptor, "owner-placeholder")
                    .thenApply(TokenDeploymentResult::txOnly);
            default -> tokenDeploymentPort.deploy(assetId, standard, chain, network, "owner-placeholder");
        };

        UUID deploymentId = saved.getId();
        txFuture.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Deployment failed for deploymentId={}", deploymentId, ex);
                completionWriter.markFailed(deploymentId, actorId, ex);
            } else {
                log.info("Deployment tx sent: deploymentId={}, txHash={}, contractAddress={}",
                        deploymentId, result.txHash(), result.contractAddress());
                completionWriter.markSubmitted(deploymentId, actorId, result);
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

    /** Resolves a deployment only when it is actually nested below the supplied asset. */
    @Transactional(readOnly = true)
    public AssetDeployment getDeployment(UUID assetId, UUID deploymentId) {
        return assetDeploymentRepository.findByIdAndAssetId(deploymentId, assetId)
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
        eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                deploymentId, null, null, contractAddress, txHash));
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
        syncFromChain(deployment);
    }

    /** Same operation with an explicit parent/child ownership invariant for REST resources. */
    public void syncFromChain(UUID assetId, UUID deploymentId) {
        syncFromChain(getDeployment(assetId, deploymentId));
    }

    private void syncFromChain(AssetDeployment deployment) {
        UUID deploymentId = deployment.getId();
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

            // A mined-but-reverted tx also yields a receipt with no contractAddress — but so does
            // every SUCCESSFUL factory/CREATE2-pattern deployment (our AssetTokenFactory/
            // EwpgTREXFactory calls, `to` = factory address, not a top-level creation tx).
            // receipt.getContractAddress() is only ever populated for a real contract-creation
            // tx, so keying success/failure on its presence — as this used to — marked every
            // successful factory-routed deployment FAILED. The receipt's own status field is the
            // correct signal; see EvmContractService.send(), which already throws on revert, so
            // in practice this branch mostly serves deployments in flight before that fix, or a
            // manual re-sync.
            if (!receipt.isStatusOK()) {
                deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
                assetDeploymentRepository.save(deployment);
                log.warn("syncFromChain: tx={} reverted on-chain (status={}); marking FAILED",
                        txHash, receipt.getStatus());
                eventPublisher.publishEvent(new DeploymentFailedEvent(
                        deploymentId, null, null, "Transaction reverted on-chain: " + txHash));
                return;
            }

            String contractAddress = deployment.getContractAddress();
            if (contractAddress == null || contractAddress.isBlank()) {
                contractAddress = extractDeployedAddress(receipt, asset(deployment).getTokenStandard()).orElse(null);
            }

            deployment.setDeployedAt(Instant.now());
            deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
            if (contractAddress != null) {
                deployment.setContractAddress(contractAddress);
            } else {
                log.warn("syncFromChain: deploymentId={} confirmed on-chain but no address-extraction " +
                        "rule for standard={}; contractAddress left unset", deploymentId,
                        asset(deployment).getTokenStandard());
            }
            assetDeploymentRepository.save(deployment);
            log.info("syncFromChain: confirmed deploymentId={} contractAddress={}", deploymentId, contractAddress);
            eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                    deploymentId, null, null, contractAddress, txHash));
        } catch (Exception e) {
            log.error("syncFromChain: error fetching receipt for deploymentId={}: {}",
                    deploymentId, e.getMessage(), e);
        }
    }

    // ── Address-extraction fallback for syncFromChain ──────────────────────────
    // Mirrors the topic constants each deploy service declares locally (Erc20/721/1155/3525
    // DeploymentService: TOKEN_DEPLOYED_TOPIC; Erc4626/7540DeploymentService: VAULT_DEPLOYED_TOPIC;
    // Erc3643DeploymentService: REGISTERWERK_SUITE_DEPLOYED_TOPIC) — this is only a fallback path
    // for deployments whose contractAddress wasn't already captured when the tx was submitted.

    private static final Set<TokenStandard> TOKEN_DEPLOYED_STANDARDS =
            EnumSet.of(TokenStandard.ERC20, TokenStandard.ERC721, TokenStandard.ERC1155, TokenStandard.ERC3525);
    private static final Set<TokenStandard> VAULT_DEPLOYED_STANDARDS =
            EnumSet.of(TokenStandard.ERC4626, TokenStandard.ERC7540);

    private static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("TokenDeployed(bytes32,uint8,address)");
    private static final String VAULT_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("VaultDeployed(bytes32,uint8,address,address)");
    private static final String REGISTERWERK_SUITE_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("EwpgSuiteDeployed(bytes32,address,address,address)");

    private Optional<String> extractDeployedAddress(TransactionReceipt receipt, TokenStandard standard) {
        if (TOKEN_DEPLOYED_STANDARDS.contains(standard)) {
            return EvmUtils.extractIndexedAddress(receipt, TOKEN_DEPLOYED_TOPIC, 3);
        }
        if (VAULT_DEPLOYED_STANDARDS.contains(standard)) {
            return EvmUtils.extractIndexedAddress(receipt, VAULT_DEPLOYED_TOPIC, 3);
        }
        if (standard == TokenStandard.ERC3643) {
            return EvmUtils.extractIndexedAddress(receipt, REGISTERWERK_SUITE_DEPLOYED_TOPIC, 2);
        }
        return Optional.empty();
    }

    private Asset asset(AssetDeployment deployment) {
        return assetRepository.findById(deployment.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", deployment.getAssetId()));
    }
}
