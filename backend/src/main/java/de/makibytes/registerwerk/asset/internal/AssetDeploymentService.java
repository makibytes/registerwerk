package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.AssetDeploymentInitiatedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.DeploymentFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentResult;
import de.makibytes.registerwerk.blockchain.api.SolanaFinalityReader;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.erc3643.api.Erc3643DeploymentPort;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /**
     * Chains where Zama's fhEVM coprocessor is (or will be) actually deployed — Ethereum and
     * Base today, per Zama's own "fhEVM Coprocessor: Run FHE smart contracts on Ethereum, Base,
     * and other EVM chains" announcement. Deliberately excludes {@code FHENIX}/{@code INCO} —
     * both real chains but running their OWN, separate, non-Zama FHE stacks with incompatible
     * libraries; Registerwerk's confidential contracts (ConfidentialERC20/ERC3643) are built
     * specifically against Zama's TFHE.sol/Gateway API and will not function against either.
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
    private final BlockchainTxProperties txProperties;
    private final ChainConfigRepository chainConfigRepository;
    private final EvmFinalityResolver finalityResolver;
    private final RestClient restClient;
    private final ChainEffectRecorder chainEffectRecorder;
    private final WalletSigner walletSigner;
    private final SolanaFinalityReader solanaFinalityReader;

    public AssetDeploymentService(
            AssetDeploymentRepository assetDeploymentRepository,
            AssetRepository assetRepository,
            ApplicationEventPublisher eventPublisher,
            TokenDeploymentPort tokenDeploymentPort,
            Erc3643DeploymentPort erc3643DeploymentPort,
            BlockchainClientRegistry blockchainClientRegistry,
            EvmContractService evmContractService,
            AssetDeploymentCompletionWriter completionWriter,
            BlockchainTxProperties txProperties,
            ChainConfigRepository chainConfigRepository,
            EvmFinalityResolver finalityResolver,
            RestClient.Builder restClientBuilder,
            ChainEffectRecorder chainEffectRecorder,
            WalletSigner walletSigner,
            SolanaFinalityReader solanaFinalityReader) {
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.tokenDeploymentPort = tokenDeploymentPort;
        this.erc3643DeploymentPort = erc3643DeploymentPort;
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.evmContractService = evmContractService;
        this.completionWriter = completionWriter;
        this.txProperties = txProperties;
        this.chainConfigRepository = chainConfigRepository;
        this.finalityResolver = finalityResolver;
        this.restClient = restClientBuilder.build();
        this.chainEffectRecorder = chainEffectRecorder;
        this.walletSigner = walletSigner;
        this.solanaFinalityReader = solanaFinalityReader;
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
        ChainConfig chainConfig = resolveEnabledChainConfig(chain, network);
        String ownerAddress = walletSigner.chainAddressForWallet(chainConfig.getId());
        if (ownerAddress == null || ownerAddress.isBlank()) {
            throw new IllegalStateException(
                    "Default wallet has no chain address for " + chainConfig.getIdentifier());
        }

        AssetDeployment deployment = new AssetDeployment();
        deployment.setAssetId(assetId);
        deployment.setChain(chain);
        deployment.setNetwork(network);
        deployment.setChainConfigId(chainConfig.getId());
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        AssetDeployment saved = assetDeploymentRepository.save(deployment);

        UUID deploymentId = saved.getId();
        ChainDescriptor descriptor = new ChainDescriptor(chain, network);
        // Never let an irreversible chain write race ahead of the PENDING deployment row. In a
        // real request this callback runs only after the surrounding transaction commits;
        // outside a transaction (plain unit tests / deliberate programmatic use) it runs now.
        AfterCommit.run(() -> startDeployment(
                deploymentId, assetId, standard, chain, network, descriptor,
                ownerAddress, actorId));

        eventPublisher.publishEvent(new AssetDeploymentInitiatedEvent(saved.getId(), actorId, null, chain.name() + "/" + network.name()));
        return saved;
    }

    private void startDeployment(
            UUID deploymentId,
            UUID assetId,
            TokenStandard standard,
            Chain chain,
            Network network,
            ChainDescriptor descriptor,
            String ownerAddress,
            UUID actorId) {
        try {
            CompletableFuture<TokenDeploymentResult> txFuture = switch (standard) {
                case ERC3643 -> erc3643DeploymentPort.deployStandard(
                        assetId, descriptor, ownerAddress);
                case CONF_ERC3643 -> erc3643DeploymentPort.deployConfidential(
                                assetId, descriptor, ownerAddress)
                        .thenApply(TokenDeploymentResult::txOnly);
                default -> tokenDeploymentPort.deploy(
                        assetId, standard, chain, network, ownerAddress);
            };
            txFuture.whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("Deployment failed for deploymentId={}", deploymentId, failure);
                    completionWriter.markFailed(deploymentId, actorId, failure);
                    return;
                }
                log.info("Deployment tx sent: deploymentId={}, txHash={}, contractAddress={}",
                        deploymentId, result.txHash(), result.contractAddress());
                completionWriter.markSubmitted(deploymentId, actorId, result);
            });
        } catch (RuntimeException failure) {
            log.error("Deployment failed before async submission for deploymentId={}",
                    deploymentId, failure);
            completionWriter.markFailed(deploymentId, actorId, failure);
        }
    }

    private ChainConfig resolveEnabledChainConfig(Chain chain, Network network) {
        List<ChainConfig> matches = chainConfigRepository
                .findByIdentifierStartingWith(chain.name() + "_").stream()
                .filter(ChainConfig::isEnabled)
                .filter(config -> config.getNetworkType().name().equals(network.name()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one enabled " + chain + "/" + network
                    + " chain configuration, found " + matches.size());
        }
        return matches.getFirst();
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
        boolean supported = switch (chain) {
            case SOLANA -> EnumSet.of(
                    TokenStandard.SPL,
                    TokenStandard.SPL_2022,
                    TokenStandard.SPL_2022_BOND,
                    TokenStandard.SPL_2022_CONFIDENTIAL).contains(standard);
            case STARKNET -> EnumSet.of(
                    TokenStandard.STARKNET_ERC20,
                    TokenStandard.STARKNET_ERC3525).contains(standard);
            case STELLAR -> standard == TokenStandard.STELLAR_ASSET;
            case CANTON -> EnumSet.of(
                    TokenStandard.DAML_BOND_FIXED,
                    TokenStandard.DAML_BOND_FLOATING,
                    TokenStandard.DAML_BOND_ZERO).contains(standard);
            default -> EnumSet.of(
                    TokenStandard.ERC20,
                    TokenStandard.ERC721,
                    TokenStandard.ERC1155,
                    TokenStandard.ERC3525,
                    TokenStandard.ERC4626,
                    TokenStandard.ERC7540,
                    TokenStandard.ERC3643,
                    TokenStandard.CONF_ERC20,
                    TokenStandard.CONF_ERC3643).contains(standard);
        };
        if (!supported) {
            throw new UnsupportedOperationException(
                    displayName(chain) + " does not support token standard " + standard);
        }
    }

    private static String displayName(Chain chain) {
        String name = chain.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
     * Queries the chain for the deployment transaction receipt and, if confirmed past the
     * configured confirmation depth ({@link BlockchainTxProperties}), marks the deployment as
     * CONFIRMED with the mined contract address.
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

    /**
     * Re-verifies every non-terminal deployment with an on-chain tx recorded, so a deployment
     * reaches CONFIRMED (once its confirmation-depth/finality policy is satisfied) without an
     * operator having to trigger a manual re-sync. Previously this was the missing half of
     * {@code AssetDeploymentCompletionWriter.markSubmitted}: EVM/Starknet deployments were left
     * PENDING with a candidate address and nothing ever polled them again.
     */
    @SchedulerLock(name = "assetDeploymentConfirmationPoll", lockAtMostFor = "PT1M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    public void pollPendingDeploymentConfirmations() {
        List<AssetDeployment> pending = assetDeploymentRepository
                .findByDeploymentStatusAndDeployedByTxIsNotNull(AssetDeployment.DeploymentStatus.PENDING);
        for (AssetDeployment deployment : pending) {
            try {
                syncFromChain(deployment);
            } catch (Exception e) {
                log.error("pollPendingDeploymentConfirmations: error syncing deploymentId={}: {}",
                        deployment.getId(), e.getMessage(), e);
            }
        }
    }

    private void syncFromChain(AssetDeployment deployment) {
        UUID deploymentId = deployment.getId();
        String txHash = deployment.getDeployedByTx();

        if (txHash == null || txHash.isBlank() || txHash.startsWith("0x-PENDING")) {
            log.debug("syncFromChain: no on-chain tx for deploymentId={}", deploymentId);
            return;
        }

        // Still a legitimate optimization, not a reorg-blind spot: this only guards *this polling
        // path* (re-verifying confirmation depth), which has nothing useful to do once CONFIRMED —
        // it is not the only mechanism watching a CONFIRMED deployment for a reorg. That job now
        // belongs to AssetDeploymentRevertCompensator, triggered independently by the finality
        // module's retraction sweep (see recordDeploymentConfirmedEffect), which reverts CONFIRMED
        // -> PENDING on retraction regardless of whether syncFromChain ever runs again for this row.
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

        Chain deployChain = deployment.getChain();

        if (deployChain == Chain.STARKNET) {
            syncStarknetDeployment(deployment);
            return;
        }
        if (deployChain == Chain.SOLANA) {
            syncSolanaDeployment(deployment);
            return;
        }
        if (deployChain == Chain.CANTON || deployChain == Chain.STELLAR) {
            // These submission adapters return only after a committed Canton transaction / closed
            // Stellar ledger. This branch repairs legacy PENDING rows created before the
            // completion writer understood their final-on-write semantics.
            deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
            deployment.setDeployedAt(Instant.now());
            assetDeploymentRepository.save(deployment);
            eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                    deploymentId, null, null, deployment.getContractAddress(), txHash));
            return;
        }

        ChainConfig confirmedChain = resolveDeploymentChainConfig(deployment);
        if (confirmedChain == null) {
            log.error("syncFromChain: deploymentId={} has no unambiguous enabled chain_config; "
                    + "refusing chain access without a durable identity", deploymentId);
            return;
        }
        Web3j web3j;
        Optional<TransactionReceipt> receiptOpt;
        try {
            web3j = blockchainClientRegistry.getEvmClientByIdentifier(confirmedChain.getIdentifier());
            receiptOpt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
        } catch (Exception e) {
            // RPC availability is retryable and occurs before any local state transition.  Do
            // not extend this catch across the mutation/journal boundary below: swallowing a
            // journal failure would commit CONFIRMED without a compensating chain_effect.
            log.error("syncFromChain: error fetching receipt for deploymentId={}: {}",
                    deploymentId, e.getMessage(), e);
            return;
        }

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

            // Reorg guard: if an earlier poll already recorded a block hash for this deployment
            // tx and the chain now reports a different hash at that height, the block was
            // reorged out before this deployment ever reached CONFIRMED. Clear the recorded
            // block and wait for the tx to be (re-)mined; mirrors
            // BlockchainTransactionCompletionWriter.resetToPendingAfterReorg.
            String newBlockHash = receipt.getBlockHash();
            if (deployment.getBlockHash() != null && newBlockHash != null
                    && !Objects.equals(deployment.getBlockHash(), newBlockHash)) {
                log.warn("syncFromChain: deploymentId={} block hash mismatch (was {}, chain now reports {} "
                                + "at that height) — deployment tx was reorged out; awaiting re-mining.",
                        deploymentId, deployment.getBlockHash(), newBlockHash);
                deployment.setBlockHash(null);
                deployment.setBlockNumber(null);
                assetDeploymentRepository.save(deployment);
                return;
            }

            Long newBlockNumber = receipt.getBlockNumber() != null
                    ? receipt.getBlockNumber().longValueExact() : null;
            boolean isFinal;
            try {
                isFinal = newBlockNumber != null
                        && finalityResolver.levelOf(confirmedChain, web3j, newBlockNumber)
                                .atLeast(FinalityLevel.FINALIZED);
            } catch (Exception e) {
                log.error("syncFromChain: error resolving finality for deploymentId={}: {}",
                        deploymentId, e.getMessage(), e);
                return;
            }
            if (!isFinal) {
                log.debug("syncFromChain: deploymentId={} mined at block {} but not yet final "
                                + "— waiting", deploymentId, newBlockNumber);
                if (!Objects.equals(deployment.getBlockHash(), newBlockHash)
                        || !Objects.equals(deployment.getBlockNumber(), newBlockNumber)) {
                    deployment.setBlockHash(newBlockHash);
                    deployment.setBlockNumber(newBlockNumber);
                    assetDeploymentRepository.save(deployment);
                }
                return;
            }
            if (newBlockHash == null || newBlockHash.isBlank()) {
                log.error("syncFromChain: deploymentId={} is final but receipt has no block hash; "
                        + "refusing to confirm without compensable provenance", deploymentId);
                return;
            }
            String contractAddress = deployment.getContractAddress();
            if (contractAddress == null || contractAddress.isBlank()) {
                contractAddress = extractDeployedAddress(receipt, asset(deployment).getTokenStandard()).orElse(null);
            }

            deployment.setDeployedAt(Instant.now());
            deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
            deployment.setBlockHash(newBlockHash);
            deployment.setBlockNumber(newBlockNumber);
            deployment.setChainConfigId(confirmedChain.getId());
            if (contractAddress != null) {
                deployment.setContractAddress(contractAddress);
            } else {
                log.warn("syncFromChain: deploymentId={} confirmed on-chain but no address-extraction " +
                        "rule for standard={}; contractAddress left unset", deploymentId,
                        asset(deployment).getTokenStandard());
            }
            assetDeploymentRepository.save(deployment);
            recordDeploymentConfirmedEffect(deployment, newBlockNumber, newBlockHash, txHash);
            eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                    deploymentId, null, null, contractAddress, txHash));
            log.info("syncFromChain: confirmed deploymentId={} contractAddress={}", deploymentId, contractAddress);
    }

    /** Resolves the exact chain row persisted with the deployment; legacy rows are accepted only
     * when chain/network identify exactly one enabled configuration. */
    private ChainConfig resolveDeploymentChainConfig(AssetDeployment deployment) {
        if (deployment.getChainConfigId() != null) {
            return chainConfigRepository.findById(deployment.getChainConfigId())
                    .filter(ChainConfig::isEnabled)
                    .orElse(null);
        }
        List<ChainConfig> matches = chainConfigRepository
                .findByIdentifierStartingWith(deployment.getChain().name() + "_").stream()
                .filter(ChainConfig::isEnabled)
                .filter(chain -> chain.getNetworkType().name().equals(deployment.getNetwork().name()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /**
     * Confirms an SPL mint only at Solana's protocol-native finalized commitment. The locally
     * generated mint address is persisted at submission time, but is not evidence that the
     * create-account transaction landed. Exact slot/block-hash provenance is journalled for the
     * shared compensation machinery before the deployment is exposed as CONFIRMED.
     */
    private void syncSolanaDeployment(AssetDeployment deployment) {
        UUID deploymentId = deployment.getId();
        if (deployment.getChainConfigId() == null) {
            log.error("syncFromChain: Solana deploymentId={} has no chainConfigId; refusing "
                    + "confirmation without durable chain identity", deploymentId);
            return;
        }
        SolanaFinalityReader.Result result;
        try {
            result = solanaFinalityReader.read(
                    deployment.getChainConfigId(), deployment.getDeployedByTx());
        } catch (Exception e) {
            log.error("syncFromChain: error checking Solana signature for deploymentId={}: {}",
                    deploymentId, e.getMessage(), e);
            return;
        }
        if (result.state() == SolanaFinalityReader.State.PENDING) {
            return;
        }
        if (result.state() == SolanaFinalityReader.State.FAILED) {
            deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
            assetDeploymentRepository.save(deployment);
            eventPublisher.publishEvent(new DeploymentFailedEvent(
                    deploymentId, null, null,
                    "Solana transaction failed: " + result.failure()));
            return;
        }
        if (result.slot() == null || result.blockHash() == null || result.blockHash().isBlank()) {
            throw new IllegalStateException("Finalized Solana result lacks slot/block hash");
        }

        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployment.setDeployedAt(Instant.now());
        deployment.setBlockNumber(result.slot());
        deployment.setBlockHash(result.blockHash());
        assetDeploymentRepository.save(deployment);
        recordDeploymentConfirmedEffect(
                deployment, result.slot(), result.blockHash(), deployment.getDeployedByTx());
        eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                deploymentId, null, null,
                deployment.getContractAddress(), deployment.getDeployedByTx()));
    }

    /**
     * Journals a {@code DEPLOYMENT_CONFIRMED} chain effect (INVERSE_FLIP) so an automatically
     * compensable routine retraction can revert the register's CONFIRMED claim, while a typed
     * finality violation retains exact effect provenance behind the chain quarantine — see
     * {@code AssetDeploymentRevertCompensator}, the compensator that undoes it. Only ever called
     * from the EVM confirmation branch above, since that is the only path that also resolves
     * {@code chainConfigId}/{@code blockNumber}. The confirmation path refuses to enter its
     * terminal state unless all provenance is available, so reaching this method without it is a
     * programming error rather than a reason to silently lose compensation coverage. Solana's
     * finalized-signature path also calls this method with its slot as {@code blockNumber}.
     */
    private void recordDeploymentConfirmedEffect(AssetDeployment deployment, Long blockNumber, String blockHash, String txHash) {
        if (deployment.getChainConfigId() == null || blockNumber == null) {
            throw new IllegalStateException("Cannot confirm deployment without chainConfigId and blockNumber");
        }
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                deployment.getChainConfigId(), blockNumber, blockHash, txHash,
                "asset", AssetDeploymentRevertCompensator.EFFECT_TYPE, "AssetDeployment", deployment.getId(),
                deployment.getAssetId(), CompensationCategory.INVERSE_FLIP));
    }

    /**
     * Starknet confirmation path: the deployment's contractAddress is UDC-precomputed and known
     * before the tx is even broadcast (see {@code StarknetTokenService}), so its presence proves
     * nothing about finality — unlike EVM, there is no block-hash reorg primitive to re-verify;
     * instead the deployment tx's own {@code finality_status} (via
     * {@code starknet_getTransactionStatus}) is the source of truth, exactly as
     * {@link de.makibytes.registerwerk.indexer.internal.StarknetTransferSyncService} already
     * does for token transfers. Only ACCEPTED_ON_L1 is treated as final; REJECTED marks the
     * deployment FAILED; every other status (RECEIVED, ACCEPTED_ON_L2, or the RPC call itself
     * failing) leaves the deployment PENDING for the next poll.
     */
    private void syncStarknetDeployment(AssetDeployment deployment) {
        UUID deploymentId = deployment.getId();
        String txHash = deployment.getDeployedByTx();
        try {
            ChainConfig.NetworkType netType = deployment.getNetwork() == Network.MAINNET
                    ? ChainConfig.NetworkType.MAINNET : ChainConfig.NetworkType.TESTNET;
            ChainConfig chain = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET).stream()
                    .filter(c -> c.getNetworkType() == netType)
                    .findFirst()
                    .orElse(null);
            if (chain == null) {
                log.warn("syncFromChain: no enabled Starknet chain config for network={}; deploymentId={}",
                        deployment.getNetwork(), deploymentId);
                return;
            }

            String finalityStatus = fetchStarknetTxFinalityStatus(chain.getRpcUrl(), txHash);
            if (finalityStatus == null) {
                log.debug("syncFromChain: could not determine Starknet finality_status for tx={} "
                        + "deploymentId={} — will retry next poll", txHash, deploymentId);
                return;
            }

            switch (finalityStatus) {
                case "ACCEPTED_ON_L1" -> {
                    deployment.setDeployedAt(Instant.now());
                    deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
                    assetDeploymentRepository.save(deployment);
                    log.info("syncFromChain: Starknet deploymentId={} confirmed (ACCEPTED_ON_L1) contractAddress={}",
                            deploymentId, deployment.getContractAddress());
                    eventPublisher.publishEvent(new DeploymentConfirmedEvent(
                            deploymentId, null, null, deployment.getContractAddress(), txHash));
                }
                case "REJECTED" -> {
                    deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.FAILED);
                    assetDeploymentRepository.save(deployment);
                    log.warn("syncFromChain: Starknet deploymentId={} tx={} REJECTED; marking FAILED",
                            deploymentId, txHash);
                    eventPublisher.publishEvent(new DeploymentFailedEvent(
                            deploymentId, null, null, "Starknet transaction rejected: " + txHash));
                }
                default -> log.debug("syncFromChain: Starknet deploymentId={} tx={} finality_status={} "
                                + "— not yet final", deploymentId, txHash, finalityStatus);
            }
        } catch (Exception e) {
            log.error("syncFromChain: error checking Starknet tx status for deploymentId={}: {}",
                    deploymentId, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchStarknetTxFinalityStatus(String rpcUrl, String txHash) {
        Map<String, Object> requestBody = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "starknet_getTransactionStatus",
                "params", List.of(txHash));
        Map<String, Object> response = restClient.post()
                .uri(rpcUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(Map.class);
        if (response == null || response.containsKey("error")) {
            return null;
        }
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map) {
            Object status = ((Map<String, Object>) map).get("finality_status");
            return status instanceof String s ? s : null;
        }
        return null;
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
