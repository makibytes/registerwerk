package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of on-chain transactions submitted by the registry.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Record a PENDING entry when a transaction is submitted</li>
 *   <li>Poll pending transactions every 5 seconds for their receipt</li>
 *   <li>Update status to SUCCESS / FAILED / TIMEOUT when receipt arrives</li>
 *   <li>Publish an audit event on completion with the actor's name</li>
 * </ul>
 */
@Service
public class BlockchainTransactionService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainTransactionService.class);

    private final BlockchainTransactionRepository repository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final ApplicationEventPublisher eventPublisher;
    private final BlockchainTxProperties txProperties;

    public BlockchainTransactionService(
            BlockchainTransactionRepository repository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ApplicationEventPublisher eventPublisher,
            BlockchainTxProperties txProperties) {
        this.repository = repository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.eventPublisher = eventPublisher;
        this.txProperties = txProperties;
    }

    // ── Record creation ───────────────────────────────────────────────────────

    /**
     * Creates a PENDING blockchain transaction record immediately after tx submission.
     * The actor name is resolved from the current Spring Security context.
     *
     * @param txHash          EVM transaction hash returned by {@link EvmContractService#submit}
     * @param methodName      smart-contract function name (e.g. "pause")
     * @param deploymentId    ID of the asset deployment (may be null)
     * @param assetId         ID of the asset (may be null)
     * @param chain           chain identifier (e.g. "ETHEREUM")
     * @param network         network identifier (e.g. "MAINNET")
     * @param contractAddress on-chain contract address
     * @param params          human-readable parameters stored for display
     * @return ID of the created {@link BlockchainTransaction} record
     */
    @Transactional
    public UUID record(String txHash, String methodName,
                       UUID deploymentId, UUID assetId,
                       String chain, String network, String contractAddress,
                       Map<String, Object> params) {

        String actorName = resolveActorName();
        String actorRole = resolveActorRole();

        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setTxHash(txHash);
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        tx.setMethodName(methodName);
        tx.setDeploymentId(deploymentId);
        tx.setAssetId(assetId);
        tx.setChain(chain);
        tx.setNetwork(network);
        tx.setContractAddress(contractAddress);
        tx.setParams(params);
        tx.setActorName(actorName);
        tx.setActorRole(actorRole);

        BlockchainTransaction saved = repository.save(tx);
        log.info("Recorded blockchain tx={} method={} actor={}", txHash, methodName, actorName);
        return saved.getId();
    }

    // ── Scheduled polling ─────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void pollPendingTransactions() {
        List<BlockchainTransaction> pending = repository.findByStatus(BlockchainTransaction.Status.PENDING);
        if (pending.isEmpty()) return;

        log.debug("Polling {} pending blockchain transactions", pending.size());

        for (BlockchainTransaction tx : pending) {
            try {
                if (tx.getTxHash() == null || tx.getChain() == null) {
                    // Cannot look up a receipt without a hash/chain — apply the un-mined timeout.
                    if (isTimedOut(tx)) markTimeout(tx);
                    continue;
                }

                ChainDescriptor descriptor = new ChainDescriptor(
                de.makibytes.registerwerk.chain.api.Chain.valueOf(tx.getChain()),
                de.makibytes.registerwerk.chain.api.Network.valueOf(tx.getNetwork()));
                Web3j web3j = clientRegistry.getEvmClient(descriptor);
                Optional<TransactionReceipt> receipt =
                        web3j.ethGetTransactionReceipt(tx.getTxHash()).send().getTransactionReceipt();

                if (receipt.isEmpty()) {
                    // Still un-mined. Only an un-mined transaction is eligible for TIMEOUT.
                    if (isTimedOut(tx)) markTimeout(tx);
                    continue;
                }

                // Mined: require the receipt to be buried under the configured confirmation
                // depth before we treat it as final. A mined-but-shallow tx stays PENDING and
                // is never timed out, so a reorg cannot leave the register asserting a dropped
                // state.
                TransactionReceipt r = receipt.get();
                int required = txProperties.confirmationsFor(tx.getChain());
                long confirmations = confirmations(web3j, r);
                if (confirmations < required) {
                    log.debug("tx={} mined at block {} but only {}/{} confirmations — waiting",
                            tx.getTxHash(), r.getBlockNumber(), confirmations, required);
                    continue;
                }
                complete(tx, r);
            } catch (Exception e) {
                log.warn("Error polling tx={}: {}", tx.getTxHash(), e.getMessage());
            }
        }
    }

    /** Number of blocks mined on top of (and including) the receipt's block. */
    private long confirmations(Web3j web3j, TransactionReceipt receipt) throws java.io.IOException {
        if (receipt.getBlockNumber() == null) {
            return 0;
        }
        java.math.BigInteger current = web3j.ethBlockNumber().send().getBlockNumber();
        java.math.BigInteger depth = current.subtract(receipt.getBlockNumber()).add(java.math.BigInteger.ONE);
        return depth.signum() < 0 ? 0 : depth.longValueExact();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @Transactional
    protected void complete(BlockchainTransaction tx, TransactionReceipt receipt) {
        boolean success = "0x1".equals(receipt.getStatus());
        tx.setStatus(success ? BlockchainTransaction.Status.SUCCESS : BlockchainTransaction.Status.FAILED);
        tx.setCompletedAt(Instant.now());
        if (receipt.getGasUsed() != null) tx.setGasUsed(receipt.getGasUsed().longValue());
        if (receipt.getBlockNumber() != null) tx.setBlockNumber(receipt.getBlockNumber().longValue());
        if (!success) tx.setErrorMessage("Transaction reverted on-chain");
        repository.save(tx);

        publishCompletionAuditEvent(tx);
        log.info("Blockchain tx={} completed status={}", tx.getTxHash(), tx.getStatus());
    }

    @Transactional
    protected void markTimeout(BlockchainTransaction tx) {
        long timeoutSeconds = txProperties.getTimeoutSeconds();
        tx.setStatus(BlockchainTransaction.Status.TIMEOUT);
        tx.setCompletedAt(Instant.now());
        tx.setErrorMessage("Transaction not mined within " + timeoutSeconds + "s");
        repository.save(tx);

        publishCompletionAuditEvent(tx);
        log.warn("Blockchain tx={} timed out", tx.getTxHash());
    }

    private boolean isTimedOut(BlockchainTransaction tx) {
        return tx.getCreatedAt().plusSeconds(txProperties.getTimeoutSeconds()).isBefore(Instant.now());
    }

    private void publishCompletionAuditEvent(BlockchainTransaction tx) {
        if (tx.getDeploymentId() == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("txHash", tx.getTxHash());
        payload.put("status", tx.getStatus().name());
        payload.put("methodName", tx.getMethodName());
        payload.put("actorName", tx.getActorName());
        payload.put("actorRole", tx.getActorRole());
        if (tx.getGasUsed() != null) payload.put("gasUsed", tx.getGasUsed());
        if (tx.getBlockNumber() != null) payload.put("blockNumber", tx.getBlockNumber());
        if (tx.getErrorMessage() != null) payload.put("error", tx.getErrorMessage());
        if (tx.getParams() != null) payload.putAll(tx.getParams());

        eventPublisher.publishEvent(new BlockchainTxStatusEvent(tx.getDeploymentId(), null, tx.getActorRole(), tx.getStatus().name(), payload));
    }

    private static String resolveActorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "system";
    }

    private static String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .findFirst()
                    .orElse("UNKNOWN");
        }
        return "UNKNOWN";
    }
}
