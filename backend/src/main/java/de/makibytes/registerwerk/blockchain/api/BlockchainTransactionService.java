package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxReviewedEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionCompletionWriter;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

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
    private final BlockchainTransactionCompletionWriter completionWriter;

    public BlockchainTransactionService(
            BlockchainTransactionRepository repository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ApplicationEventPublisher eventPublisher,
            BlockchainTxProperties txProperties,
            BlockchainTransactionCompletionWriter completionWriter) {
        this.repository = repository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.eventPublisher = eventPublisher;
        this.txProperties = txProperties;
        this.completionWriter = completionWriter;
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

    /**
     * Annotates a FAILED/TIMEOUT transaction as handled — the global transaction console's only
     * write action. Not a resubmit: no nonce or unsigned calldata is captured at submission
     * time (only display-only {@code params}), so a safe gas-bump resubmit through the shared
     * signing path in {@link EvmContractService} isn't implemented here. This exists so ops has
     * somewhere to record what was actually done about a stuck/reverted transaction instead of
     * it sitting unactionable forever.
     */
    @Transactional
    public BlockchainTransaction review(UUID id, UUID actorId, String actorRole, String note) {
        BlockchainTransaction tx = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("BlockchainTransaction", id));
        if (tx.getStatus() != BlockchainTransaction.Status.FAILED
                && tx.getStatus() != BlockchainTransaction.Status.TIMEOUT) {
            throw new InvalidStateTransitionException(
                    "BlockchainTransaction", tx.getStatus().name(), "REVIEWED (only FAILED/TIMEOUT can be annotated)");
        }
        tx.setOpsNote(note);
        tx.setOpsReviewedAt(Instant.now());
        tx.setOpsReviewedBy(actorId);
        BlockchainTransaction saved = repository.save(tx);

        eventPublisher.publishEvent(new BlockchainTxReviewedEvent(id, actorId, actorRole, Map.of(
                "txHash", String.valueOf(tx.getTxHash()),
                "status", tx.getStatus().name(),
                "note", note
        )));
        return saved;
    }

    // ── Scheduled polling ─────────────────────────────────────────────────────

    @SchedulerLock(name = "blockchainTxPoller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT4S")
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void pollPendingTransactions() {
        List<BlockchainTransaction> pending = repository.findByStatus(BlockchainTransaction.Status.PENDING);
        if (pending.isEmpty()) return;

        log.debug("Polling {} pending blockchain transactions", pending.size());

        for (BlockchainTransaction tx : pending) {
            try {
                if (tx.getTxHash() == null || tx.getChain() == null) {
                    // Cannot look up a receipt without a hash/chain — apply the un-mined timeout.
                    if (isTimedOut(tx)) completionWriter.markTimeout(tx, txProperties.getTimeoutSeconds());
                    continue;
                }

                // Phase 3 fix: getEvmClient(ChainDescriptor) is the legacy static-client tier —
                // it bypasses BlockchainClientRegistry's node pool entirely, so this poller
                // never benefited from multi-node failover/health-aware selection and would
                // throw outright for any chain configured only via the node pool (no static
                // property entry). getEvmClientByIdentifier picks the best currently-healthy
                // rpc_node every tick (RpcNodeHealthService refreshes health ~every 30s), so a
                // node that starts failing is avoided on the very next poll instead of never.
                String identifier = tx.getChain() + "_" + tx.getNetwork();
                Web3j web3j = clientRegistry.getEvmClientByIdentifier(identifier);
                Optional<TransactionReceipt> receipt =
                        web3j.ethGetTransactionReceipt(tx.getTxHash()).send().getTransactionReceipt();

                if (receipt.isEmpty()) {
                    // Still un-mined. Only an un-mined transaction is eligible for TIMEOUT.
                    if (isTimedOut(tx)) completionWriter.markTimeout(tx, txProperties.getTimeoutSeconds());
                    continue;
                }

                TransactionReceipt r = receipt.get();

                // Reorg guard: if we already recorded a block hash for this tx on an earlier poll
                // (below), and the chain now reports a different hash at that height, this tx's
                // block was reorged out — reset to PENDING rather than trusting a confirmation
                // count built on a block that no longer exists. Not a resubmit (see writer
                // javadoc); the tx may simply need to be re-mined.
                if (tx.getBlockHash() != null && r.getBlockHash() != null
                        && !Objects.equals(tx.getBlockHash(), r.getBlockHash())) {
                    completionWriter.resetToPendingAfterReorg(tx, r.getBlockHash());
                    continue;
                }

                // Mined: require the receipt to be buried under the configured confirmation
                // depth before we treat it as final. A mined-but-shallow tx stays PENDING and
                // is never timed out, so a reorg cannot leave the register asserting a dropped
                // state.
                int required = txProperties.confirmationsFor(tx.getChain());
                long confirmations = EvmUtils.confirmations(web3j, r);
                if (confirmations < required) {
                    log.debug("tx={} mined at block {} but only {}/{} confirmations — waiting",
                            tx.getTxHash(), r.getBlockNumber(), confirmations, required);
                    // Persist the block hash/number the first time we see them (or if they moved)
                    // so the mismatch check above has a baseline to compare against next poll,
                    // even before the confirmation depth is reached.
                    Long receiptBlockNumber = r.getBlockNumber() != null ? r.getBlockNumber().longValueExact() : null;
                    if (!Objects.equals(tx.getBlockHash(), r.getBlockHash())
                            || !Objects.equals(tx.getBlockNumber(), receiptBlockNumber)) {
                        completionWriter.recordProvisionalReceipt(tx, r);
                    }
                    continue;
                }
                completionWriter.complete(tx, r);
            } catch (Exception e) {
                log.warn("Error polling tx={}: {}", tx.getTxHash(), e.getMessage());
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private boolean isTimedOut(BlockchainTransaction tx) {
        return tx.getCreatedAt().plusSeconds(txProperties.getTimeoutSeconds()).isBefore(Instant.now());
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
