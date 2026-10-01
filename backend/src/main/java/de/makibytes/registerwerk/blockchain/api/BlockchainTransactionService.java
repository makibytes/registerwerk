package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxReviewedEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionCompletionWriter;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.blockchain.internal.tx.OutboxNonceResolver;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
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
 *   <li>Update status to SUCCESS / FAILED when the receipt arrives, TIMEOUT when it does not (TIMEOUT
 *       is not terminal: such rows keep being reconciled for the late-mined window), REPLACED when
 *       the nonce is proven consumed by another transaction</li>
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
    private final EvmFinalityResolver finalityResolver;
    private final ChainConfigRepository chainConfigRepository;
    private final OutboxNonceResolver nonceResolver;
    private final SecondSourceConfirmer secondSource;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final java.util.concurrent.atomic.AtomicInteger pendingPage = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger lateMinedPage = new java.util.concurrent.atomic.AtomicInteger();

    public BlockchainTransactionService(
            BlockchainTransactionRepository repository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ApplicationEventPublisher eventPublisher,
            BlockchainTxProperties txProperties,
            BlockchainTransactionCompletionWriter completionWriter,
            EvmFinalityResolver finalityResolver,
            ChainConfigRepository chainConfigRepository,
            OutboxNonceResolver nonceResolver,
            SecondSourceConfirmer secondSource) {
        this(repository, clientRegistry, evmContractService, eventPublisher, txProperties, completionWriter,
                finalityResolver, chainConfigRepository, nonceResolver, secondSource,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BlockchainTransactionService(
            BlockchainTransactionRepository repository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            ApplicationEventPublisher eventPublisher,
            BlockchainTxProperties txProperties,
            BlockchainTransactionCompletionWriter completionWriter,
            EvmFinalityResolver finalityResolver,
            ChainConfigRepository chainConfigRepository,
            OutboxNonceResolver nonceResolver,
            SecondSourceConfirmer secondSource,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.repository = repository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.eventPublisher = eventPublisher;
        this.txProperties = txProperties;
        this.completionWriter = completionWriter;
        this.finalityResolver = finalityResolver;
        this.chainConfigRepository = chainConfigRepository;
        this.nonceResolver = nonceResolver;
        this.secondSource = secondSource;
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

        // The durable path has already recorded this hash in the same DB transaction
        // (DurableEvmSubmissionService.prepare): complete that row with the business links instead of
        // inserting a second one, which would violate tx_hash UNIQUE (and did, before P4B-7's IT).
        Optional<BlockchainTransaction> prepared = repository.findByTxHash(txHash);
        if (prepared.isPresent()) {
            BlockchainTransaction existing = prepared.get();
            if (existing.getDeploymentId() == null) {
                existing.setDeploymentId(deploymentId);
            }
            if (existing.getAssetId() == null) {
                existing.setAssetId(assetId);
            }
            return repository.save(existing).getId();
        }
        String actorName = resolveActorName();
        String actorRole = resolveActorRole();
        UUID chainConfigId = null;
        if (chain != null && network != null) {
            chainConfigId = chainConfigRepository.findByIdentifier(chain + "_" + network)
                    .map(de.makibytes.registerwerk.chain.api.ChainConfig::getId).orElse(null);
        }
        return createPending(txHash, methodName, deploymentId, assetId, chainConfigId,
                chain, network, contractAddress, params, actorName, actorRole);
    }

    /**
     * Records a durable signed-outbox transaction only after its exact bytes are known to the
     * RPC node. The original actor is explicit because scheduled retries no longer run in that
     * actor's security context. Re-entry is idempotent for the outbox's immutable tx hash.
     */
    @Transactional
    public UUID recordPrepared(String txHash, String methodName, UUID chainConfigId,
            String chain, String network, String contractAddress, Map<String, Object> params,
            String actorName, String actorRole) {
        Optional<BlockchainTransaction> existing = repository.findByTxHash(txHash);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        return createPending(txHash, methodName, null, null, chainConfigId,
                chain, network, contractAddress, params, actorName, actorRole);
    }

    /** P4B-7: stamps the request's Idempotency-Key on the transaction record (no-op without a key). */
    @Transactional
    public void tagIdempotencyKey(String txHash, String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        repository.findByTxHash(txHash).ifPresent(tx -> {
            tx.setIdempotencyKey(idempotencyKey);
            repository.save(tx);
        });
    }

    private UUID createPending(String txHash, String methodName,
            UUID deploymentId, UUID assetId, UUID chainConfigId,
            String chain, String network, String contractAddress,
            Map<String, Object> params, String actorName, String actorRole) {
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
        tx.setChainConfigId(chainConfigId);

        // P4C-4: 4-eyes evidence + optional case reference of the originating HTTP request (set by
        // the step-up interceptor / read from X-Case-Reference); absent for scheduled retries.
        tx.setApproverId(RequestEvidence.approverId());
        tx.setCaseReference(RequestEvidence.caseReference());

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
                && tx.getStatus() != BlockchainTransaction.Status.TIMEOUT
                && tx.getStatus() != BlockchainTransaction.Status.REPLACED) {
            throw new InvalidStateTransitionException(
                    "BlockchainTransaction", tx.getStatus().name(),
                    "REVIEWED (only FAILED/TIMEOUT/REPLACED can be annotated)");
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

    /**
     * Notes that a re-price / cancel replacement was issued for {@code originalTxHash}'s nonce
     * (P4B-4), so the console can show it. The original stays PENDING/TIMEOUT: the poller decides
     * which of the hashes mined.
     */
    @Transactional
    public void noteReplacement(String originalTxHash, String replacementTxHash) {
        repository.findByTxHash(originalTxHash).ifPresent(tx -> {
            tx.setReplacedByTxHash(replacementTxHash);
            repository.save(tx);
        });
    }

    // ── Finality lookup for other modules' pollers ───────────────────────────

    /**
     * True once {@link #pollPendingTransactions} has confirmed {@code txHash} SUCCESS — i.e. it
     * has cleared the chain's configured {@code FinalityModel} (a real {@code finalized} tag,
     * confirmation depth, or immediate for permissioned BFT), not merely been mined once. False
     * while still tracked-PENDING or if {@code txHash} isn't a tracked transaction at all.
     *
     * <p>For any module that submits via {@link #record} and needs to know "is this specific
     * action done and successful yet" — reusing this tracked, already-model-aware verdict is
     * preferred over each caller re-implementing its own receipt/confirmation check, which is
     * exactly the inconsistency this method exists to close (see {@code MarketplaceTxPoller},
     * which used to accept the first mined receipt as final regardless of chain or depth).
     */
    @Transactional(readOnly = true)
    public boolean isConfirmedSuccess(String txHash) {
        return repository.findByTxHash(txHash)
                .map(tx -> tx.getStatus() == BlockchainTransaction.Status.SUCCESS)
                .orElse(false);
    }

    /**
     * True only when {@code txHash} is known never to have executed and is safe to resubmit: it was
     * mined and reverted ({@code FAILED}), or its nonce was consumed by a different transaction
     * ({@code REPLACED}: a cancel replacement or an outside signer). <strong>{@code TIMEOUT} is not a
     * failure</strong> (P4B-5): the transaction was merely not mined within the timeout and may still
     * be, so a caller that clears its state and resubmits on TIMEOUT could execute the operation twice.
     * The poller keeps reconciling TIMEOUT rows; callers wait ({@link #isAwaitingChain}) and the
     * operator uses the outbox cancel / re-price actions. @see #isConfirmedSuccess
     */
    @Transactional(readOnly = true)
    public boolean isConfirmedFailure(String txHash) {
        return repository.findByTxHash(txHash)
                .map(tx -> tx.getStatus() == BlockchainTransaction.Status.FAILED
                        || tx.getStatus() == BlockchainTransaction.Status.REPLACED)
                .orElse(false);
    }

    /** True while {@code txHash} timed out un-mined but may still execute: neither success nor failure. */
    @Transactional(readOnly = true)
    public boolean isAwaitingChain(String txHash) {
        return repository.findByTxHash(txHash)
                .map(tx -> tx.getStatus() == BlockchainTransaction.Status.TIMEOUT)
                .orElse(false);
    }

    /** Where a tracked, SUCCESS transaction was confirmed — {@code chainConfigId} and
     *  {@code blockNumber} (as resolved/recorded by {@link #record} and
     *  {@link de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionCompletionWriter#complete}),
     *  handed to callers that need it to journal a {@code ChainEffectDescriptor} for a confirmed
     *  action that isn't itself a tracked entity with its own block-number column (e.g.
     *  {@code Erc3643IdentityRegistryConfirmationListener}). Empty if not tracked, not SUCCESS
     *  yet, or complete chain/block provenance is unavailable. */
    public record ConfirmedTxLocation(java.util.UUID chainConfigId, long blockNumber, String blockHash) {}

    @Transactional(readOnly = true)
    public Optional<ConfirmedTxLocation> confirmedLocation(String txHash) {
        return repository.findByTxHash(txHash)
                .filter(tx -> tx.getStatus() == BlockchainTransaction.Status.SUCCESS
                        && tx.getChainConfigId() != null && tx.getBlockNumber() != null
                        && tx.getBlockHash() != null && !tx.getBlockHash().isBlank())
                .map(tx -> new ConfirmedTxLocation(tx.getChainConfigId(), tx.getBlockNumber(), tx.getBlockHash()));
    }

    // ── Scheduled polling ─────────────────────────────────────────────────────

    @SchedulerLock(name = "blockchainTxPoller", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void pollPendingTransactions() {
        int size = Math.max(1, txProperties.getPollBatchSize());
        List<BlockchainTransaction> pending = repository.findByStatusOrderByCreatedAtAsc(
                BlockchainTransaction.Status.PENDING,
                org.springframework.data.domain.PageRequest.of(pendingPage.get(), size));
        // Rotate through pages so rows beyond the first batch (still un-mined) are not starved.
        pendingPage.set(pending.size() == size ? pendingPage.get() + 1 : 0);
        if (pending.isEmpty()) return;

        log.debug("Polling {} pending blockchain transactions", pending.size());
        pollBounded(pending, "pending");
    }

    /** Polls rows until the run deadline (7A-06); rows not reached stay as they are and are picked up next tick. */
    private void pollBounded(List<BlockchainTransaction> rows, String kind) {
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(txProperties.getPollDeadlineSeconds());
        long budgetNanos = deadlineNanos - System.nanoTime();
        for (BlockchainTransaction tx : rows) {
            if (System.nanoTime() >= deadlineNanos) {
                log.warn("Blockchain tx poller ({}) reached its run deadline; remaining rows wait for the next run", kind);
                meterRegistry.counter("registerwerk_blockchain_tx_poll_deadline_reached_total", "poller", kind).increment();
                return;
            }
            pollOne(tx);
        }
        if (System.nanoTime() - (deadlineNanos - budgetNanos) > budgetNanos * 8 / 10) {
            meterRegistry.counter("registerwerk_blockchain_tx_poll_over_80pct_budget_total", "poller", kind).increment();
        }
    }

    /**
     * P4B-5: TIMEOUT is "not yet mined", not a verdict. Rows younger than the late-mined window keep
     * being read (a slower cadence than PENDING); a receipt completes them through the normal path and
     * leaves a late-mined audit event, and a nonce proven consumed by another transaction turns them
     * REPLACED.
     */
    @SchedulerLock(name = "blockchainTxLateMinedPoller", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void pollTimedOutTransactions() {
        Instant cutoff = Instant.now().minusSeconds(txProperties.getLateMinedWindowSeconds());
        int size = Math.max(1, txProperties.getPollBatchSize());
        List<BlockchainTransaction> timedOut = repository.findByStatusAndCompletedAtAfterOrderByCompletedAtAsc(
                BlockchainTransaction.Status.TIMEOUT, cutoff,
                org.springframework.data.domain.PageRequest.of(lateMinedPage.get(), size));
        lateMinedPage.set(timedOut.size() == size ? lateMinedPage.get() + 1 : 0);
        if (timedOut.isEmpty()) return;

        log.debug("Reconciling {} timed-out blockchain transactions", timedOut.size());
        pollBounded(timedOut, "late_mined");
    }

    private void pollOne(BlockchainTransaction tx) {
        boolean timedOutRow = tx.getStatus() == BlockchainTransaction.Status.TIMEOUT;
        try {
            if (tx.getTxHash() == null || tx.getChain() == null) {
                // Cannot look up a receipt without a hash/chain — apply the un-mined timeout.
                if (!timedOutRow && isTimedOut(tx)) completionWriter.markTimeout(tx, txProperties.getTimeoutSeconds());
                return;
            }

            // getEvmClient(ChainDescriptor) is the legacy static-client tier —
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
            String minedHash = null;
            boolean cancelReceipt = false;

            if (receipt.isEmpty()) {
                boolean unmined = timedOutRow || isTimedOut(tx);
                if (unmined) {
                    // Only an un-mined transaction is eligible for TIMEOUT - and for the nonce
                    // reconciliation: a re-priced replacement may have mined instead, a cancel may
                    // have consumed the nonce, or somebody else did (REPLACED).
                    OutboxNonceResolver.Resolution resolution = nonceResolver.resolve(tx, web3j,
                            java.time.Duration.ofSeconds(txProperties.getReplacedConfirmationSeconds()));
                    if (resolution instanceof OutboxNonceResolver.MinedAsReplacement m) {
                        receipt = Optional.of(m.receipt());
                        minedHash = m.hash();
                    } else if (resolution instanceof OutboxNonceResolver.MinedAsCancel c) {
                        receipt = Optional.of(c.receipt());
                        minedHash = c.hash();
                        cancelReceipt = true;
                    } else if (resolution instanceof OutboxNonceResolver.NonceConsumed n) {
                        String reason = "nonce consumed by another transaction (chain count " + n.chainCount() + ")";
                        completionWriter.markReplaced(tx, null, reason);
                        nonceResolver.abandonNonce(tx, null, reason, "system", null);
                        return;
                    }
                }
                if (receipt.isEmpty()) {
                    if (!timedOutRow && isTimedOut(tx)) completionWriter.markTimeout(tx, txProperties.getTimeoutSeconds());
                    return;
                }
            }

            TransactionReceipt r = receipt.get();

            // Reorg guard: if we already recorded a block hash for this tx on an earlier poll
            // (below), and the chain now reports a different hash at that height, this tx's
            // block was reorged out — reset to PENDING rather than trusting a confirmation
            // count built on a block that no longer exists. Not a resubmit (see writer
            // javadoc); the tx may simply need to be re-mined.
            if (!cancelReceipt && tx.getBlockHash() != null && r.getBlockHash() != null
                    && !Objects.equals(tx.getBlockHash(), r.getBlockHash())) {
                completionWriter.resetToPendingAfterReorg(tx, r.getBlockHash());
                return;
            }

            // Mined: require the receipt to be final under the chain's configured
            // FinalityModel before we treat it as final. A mined-but-not-yet-final tx stays
            // PENDING and is never timed out, so a reorg cannot leave the register asserting
            // a dropped state.
            long blockNumber = r.getBlockNumber().longValueExact();
            boolean isFinal = finalityResolver.levelOf(identifier, web3j, blockNumber)
                    .atLeast(FinalityLevel.FINALIZED);
            if (!isFinal) {
                log.debug("tx={} mined at block {} but not yet final — waiting",
                        tx.getTxHash(), r.getBlockNumber());
                // Persist the block hash/number the first time we see them (or if they moved)
                // so the mismatch check above has a baseline to compare against next poll,
                // even before the confirmation depth is reached. (Never for a cancel receipt: that
                // block belongs to the replacement, not to this transaction.)
                Long receiptBlockNumber = r.getBlockNumber() != null ? r.getBlockNumber().longValueExact() : null;
                if (!cancelReceipt && (!Objects.equals(tx.getBlockHash(), r.getBlockHash())
                        || !Objects.equals(tx.getBlockNumber(), receiptBlockNumber))) {
                    completionWriter.recordProvisionalReceipt(tx, r);
                }
                return;
            }
            if (cancelReceipt) {
                String reason = "cancelled: nonce consumed by cancel transaction " + minedHash;
                completionWriter.markReplaced(tx, minedHash, reason);
                nonceResolver.abandonNonce(tx, minedHash, reason, "system", null);
                return;
            }
            // P4C-6: a registry-mutating transaction is completed only when a second node agrees.
            if (txProperties.requiresSecondSource(tx.getMethodName())) {
                SecondSourceConfirmer.Verdict verdict = secondSource.confirm(identifier,
                        minedHash != null ? minedHash : tx.getTxHash(), tx.getContractAddress(), r);
                if (verdict == SecondSourceConfirmer.Verdict.HOLD_PENDING
                        || verdict == SecondSourceConfirmer.Verdict.HOLD_MISMATCH) {
                    log.warn("tx={} held: second-source confirmation {}", tx.getTxHash(), verdict);
                    return;
                }
            }
            completionWriter.complete(tx, r, minedHash);
        } catch (Exception e) {
            log.warn("Error polling tx={}: {}", tx.getTxHash(), e.getMessage());
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
