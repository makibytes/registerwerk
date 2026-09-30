package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.events.OutboxRecoveryEvent;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransaction;
import de.makibytes.registerwerk.blockchain.internal.tx.BlockchainTransactionRepository;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recovery of the durable EVM outbox (P4B-4 / parked T4-03 interim).
 *
 * <ul>
 *   <li><b>Automatic, narrow:</b> a PREPARED payload that was never accepted by any node and keeps
 *       failing with a fee-related class is re-signed at the same nonce with fresh fees (within the
 *       K4a fee ceilings) - but only for allow-listed, non-regulatory calls. Regulatory calls
 *       (forced transfer, burn, freeze, ...) are never replaced automatically.</li>
 *   <li><b>Automatic, harmless:</b> a BROADCAST payload the node no longer knows is re-broadcast with
 *       the stored bytes (no re-sign) after {@code rebroadcast-after}.</li>
 *   <li><b>Operator:</b> {@link #reprice} and {@link #cancel} (0-value self-send at the same nonce),
 *       exposed under step-up + 4-eyes by {@code OutboxAdminController}.</li>
 *   <li><b>Visibility:</b> stuck rows raise a one-time audit event and log, and per-signer gauges
 *       ({@code registerwerk.outbox.oldest_prepared_age_seconds}, {@code prepared_count},
 *       {@code stuck_count}) feed the 10-minute page.</li>
 * </ul>
 *
 * The original row becomes ABANDONED (and its {@code blockchain_transaction} REPLACED, which business
 * listeners treat as "failed, safe to resubmit") only after the replacing transaction is final; see
 * {@code OutboxNonceResolver} and the poller in {@link BlockchainTransactionService}.
 */
@Service
public class OutboxRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryService.class);

    /** Who is acting; {@code approverId} is the validated second approver of an operator action. */
    public record Actor(UUID id, String name, String role, UUID approverId, UUID correlationId) {
        public static Actor system() {
            return new Actor(null, "system", "SYSTEM", null, null);
        }
    }

    /** One outbox row for the operator console (the frontend queue is K8). */
    public record OutboxEntry(UUID id, UUID chainConfigId, String senderAddress, BigInteger nonce, String txHash,
            String methodName, String contractAddress, String status, String kind, int attemptCount,
            String lastError, String lastErrorClass, Instant createdAt, Instant firstFailedAt,
            Instant nextAttemptAt, Instant broadcastAt, long ageSeconds, String replacesTxHash,
            String supersededByTxHash, String transactionStatus, boolean regulatory,
            boolean autoRepriceAllowed, BigInteger maxFeePerGasWei, BigInteger maxPriorityFeePerGasWei,
            int rebroadcastCount) {}

    private final EvmSignedSubmissionRepository repository;
    private final BlockchainTransactionRepository transactions;
    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final DurableEvmSubmissionPort submissions;
    private final ApplicationEventPublisher events;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    private final IsolatedTransactionExecutor isolated;

    private final Map<String, AtomicLong> oldestAge = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> preparedCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stuckCount = new ConcurrentHashMap<>();

    public OutboxRecoveryService(EvmSignedSubmissionRepository repository,
            BlockchainTransactionRepository transactions,
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            DurableEvmSubmissionPort submissions,
            ApplicationEventPublisher events,
            OutboxProperties properties,
            MeterRegistry meters,
            IsolatedTransactionExecutor isolated) {
        this.repository = repository;
        this.transactions = transactions;
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.submissions = submissions;
        this.events = events;
        this.properties = properties;
        this.meters = meters;
        this.isolated = isolated;
    }

    // ── Automatic escalation (called by the dispatcher after a failed attempt) ───────────────

    /**
     * Re-prices an allow-listed, non-regulatory PREPARED payload that keeps failing for fee reasons.
     * A no-op for everything else: those rows stay PREPARED, keep backing off, and are surfaced by the
     * stuck alert for an operator decision.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalate(UUID submissionId) {
        EvmSignedSubmission row = repository.findByIdForUpdate(submissionId).orElse(null);
        if (row == null || row.getStatus() != EvmSignedSubmission.Status.PREPARED
                || row.getKind() == EvmSignedSubmission.Kind.CANCEL) {
            return;
        }
        if (!BroadcastErrorClassifier.isFeeRelated(row.getLastErrorClass())
                || row.getAttemptCount() < properties.getAutoRepriceAfterAttempts()) {
            return;
        }
        if (!properties.mayAutoReprice(row.getMethodName())) {
            log.warn("Outbox row {} ({}) is stuck with {} but is not eligible for automatic re-pricing; "
                    + "an operator must re-price or cancel it.", row.getId(), row.getMethodName(),
                    row.getLastErrorClass());
            return;
        }
        try {
            replace(row.getChainConfigId(), row.getId(), EvmContractService.ReplacementKind.REPRICE,
                    Actor.system(), "automatic re-price after " + row.getAttemptCount() + " "
                            + row.getLastErrorClass() + " broadcast failures");
        } catch (RuntimeException e) {
            // e.g. FeeAboveCeilingException: the cap wins; the row stays for the operator.
            log.warn("Automatic re-price of outbox row {} refused: {}", row.getId(), e.getMessage());
        }
    }

    // ── Operator actions ─────────────────────────────────────────────────────────────────

    /** Re-signs the same call at the same nonce with a fee at least the configured percentage higher. */
    @Transactional
    public OutboxEntry reprice(UUID chainConfigId, UUID submissionId, Actor actor, String reason) {
        return toEntry(replace(chainConfigId, submissionId, EvmContractService.ReplacementKind.REPRICE, actor, reason));
    }

    /** Replaces the payload with a 0-value self-send at the same nonce: the operation will not execute. */
    @Transactional
    public OutboxEntry cancel(UUID chainConfigId, UUID submissionId, Actor actor, String reason) {
        return toEntry(replace(chainConfigId, submissionId, EvmContractService.ReplacementKind.CANCEL, actor, reason));
    }

    @Transactional(readOnly = true)
    public List<OutboxEntry> listStuck(UUID chainConfigId) {
        return repository.findStuck(chainConfigId, Instant.now().minus(properties.getStuckAfter())).stream()
                .map(this::toEntry).toList();
    }

    private EvmSignedSubmission replace(UUID chainConfigId, UUID submissionId,
            EvmContractService.ReplacementKind kind, Actor actor, String reason) {
        EvmSignedSubmission row = repository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("EvmSignedSubmission", submissionId));
        if (!row.getChainConfigId().equals(chainConfigId)) {
            throw new EntityNotFoundException("EvmSignedSubmission", submissionId);
        }
        if (row.getStatus() != EvmSignedSubmission.Status.PREPARED
                && row.getStatus() != EvmSignedSubmission.Status.BROADCAST) {
            throw new InvalidStateTransitionException("EvmSignedSubmission", row.getStatus().name(),
                    kind + " (only the live PREPARED/BROADCAST payload of a nonce can be replaced)");
        }
        ChainConfig chain = chainConfigRepository.findById(row.getChainConfigId())
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", row.getChainConfigId()));
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        try {
            if (web3j.ethGetTransactionReceipt(row.getTxHash()).send().getTransactionReceipt().isPresent()) {
                throw new InvalidStateTransitionException(
                        "Transaction " + row.getTxHash() + " is already mined; nothing to replace");
            }
            BigInteger mined = evmContractService.transactionCount(web3j, row.getSenderAddress(),
                    DefaultBlockParameterName.LATEST);
            if (mined.compareTo(row.getNonce()) > 0) {
                throw new InvalidStateTransitionException("Nonce " + row.getNonce() + " of "
                        + row.getSenderAddress() + " is already consumed on-chain (count " + mined
                        + "); wait for the poller to reconcile it");
            }
        } catch (InvalidStateTransitionException e) {
            throw e;
        } catch (Exception e) {
            throw new de.makibytes.registerwerk.shared.TransientChainException(
                    "Could not read the chain state needed to replace the payload: " + e.getMessage());
        }

        EvmSigner signer = evmContractService.signer(row.getChainConfigId());
        if (!signer.address().equalsIgnoreCase(row.getSenderAddress())) {
            throw new IllegalStateException("The payload was signed by " + row.getSenderAddress()
                    + " but the chain's default signer is now " + signer.address()
                    + "; restore that wallet as default before replacing the payload");
        }
        EvmContractService.PreparedRawTransaction prepared = evmContractService.resign(
                row.getChainConfigId(), web3j, signer, row.getSignedPayload(), kind,
                properties.getReplacementBumpPercent());

        // A replacement of a cancel is a cancel again; a re-price of anything keeps its call.
        EvmSignedSubmission.Kind newKind = (kind == EvmContractService.ReplacementKind.CANCEL
                || row.getKind() == EvmSignedSubmission.Kind.CANCEL)
                ? EvmSignedSubmission.Kind.CANCEL : EvmSignedSubmission.Kind.REPRICE;
        String rootHash = rootHash(row);

        // Free the nonce for the replacement (partial unique index over active rows) before inserting.
        row.setStatus(EvmSignedSubmission.Status.SUPERSEDED);
        row.setSupersededByTxHash(prepared.txHash());
        repository.saveAndFlush(row);

        EvmSignedSubmission replacement = new EvmSignedSubmission();
        replacement.setChainConfigId(row.getChainConfigId());
        replacement.setChainId(row.getChainId());
        replacement.setSenderAddress(row.getSenderAddress());
        replacement.setNonce(row.getNonce());
        replacement.setTxHash(prepared.txHash());
        replacement.setSignedPayload(prepared.signedPayload());
        replacement.setChainName(row.getChainName());
        replacement.setNetwork(row.getNetwork());
        replacement.setKind(newKind);
        replacement.setReplacesTxHash(row.getTxHash());
        replacement.setActorName(actor.name());
        replacement.setActorRole(actor.role() != null ? actor.role() : "SYSTEM");
        if (newKind == EvmSignedSubmission.Kind.CANCEL) {
            replacement.setContractAddress(row.getSenderAddress());
            replacement.setMethodName("cancelNonce");
            Map<String, Object> params = new HashMap<>();
            params.put("cancelsTxHash", rootHash);
            params.put("reason", reason);
            replacement.setParams(params);
        } else {
            replacement.setContractAddress(row.getContractAddress());
            replacement.setMethodName(row.getMethodName());
            replacement.setParams(row.getParams());
        }
        repository.saveAndFlush(replacement);

        // The business layer holds the root hash; point its record at the latest replacement. A cancel
        // is its own on-chain transaction and gets its own record (a cancel replaced by a cancel is
        // tracked through the earlier cancel's record).
        txService.noteReplacement(rootHash, prepared.txHash());
        if (newKind == EvmSignedSubmission.Kind.CANCEL && row.getKind() != EvmSignedSubmission.Kind.CANCEL) {
            txService.recordPrepared(replacement.getTxHash(), replacement.getMethodName(),
                    replacement.getChainConfigId(), replacement.getChainName(), replacement.getNetwork(),
                    replacement.getContractAddress(), replacement.getParams(),
                    replacement.getActorName(), replacement.getActorRole());
        }

        Map<String, Object> details = new HashMap<>();
        details.put("kind", newKind.name());
        details.put("automatic", actor.id() == null && "system".equals(actor.name()));
        details.put("nonce", row.getNonce().toString());
        details.put("sender", row.getSenderAddress());
        details.put("method", row.getMethodName());
        details.put("replacedTxHash", row.getTxHash());
        details.put("replacementTxHash", prepared.txHash());
        details.put("rootTxHash", rootHash);
        details.put("reason", reason);
        publish(new OutboxRecoveryEvent(row.getId(),
                newKind == EvmSignedSubmission.Kind.CANCEL ? "CANCELLED" : "REPRICED",
                actor.id(), actor.role() != null ? actor.role() : "SYSTEM", actor.approverId(),
                actor.correlationId(), details));
        meters.counter("registerwerk.outbox.replaced", "kind", newKind.name(),
                "mode", details.get("automatic").equals(Boolean.TRUE) ? "auto" : "operator").increment();
        log.warn("Outbox row {} (nonce {} of {}) replaced by {} tx {} ({})", row.getId(), row.getNonce(),
                row.getSenderAddress(), newKind, prepared.txHash(), reason);

        UUID replacementId = replacement.getId();
        AfterCommit.run(() -> {
            try {
                submissions.dispatch(replacementId);
            } catch (RuntimeException e) {
                log.warn("Replacement {} stays PREPARED for the dispatcher: {}", replacementId, e.getMessage());
            }
        });
        return replacement;
    }

    private String rootHash(EvmSignedSubmission row) {
        EvmSignedSubmission cursor = row;
        for (int i = 0; i < 32 && cursor.getKind() == EvmSignedSubmission.Kind.REPRICE
                && cursor.getReplacesTxHash() != null; i++) {
            Optional<EvmSignedSubmission> parent = repository.findByTxHash(cursor.getReplacesTxHash());
            if (parent.isEmpty()) break;
            cursor = parent.get();
        }
        return cursor.getTxHash();
    }

    // ── Sweep: re-broadcast, stuck detection, gauges ──────────────────────────────────────

    @SchedulerLock(name = "evmOutboxRecoverySweep", lockAtMostFor = "PT4M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void sweep() {
        try {
            rebroadcastVanished();
        } catch (RuntimeException e) {
            log.warn("Outbox re-broadcast sweep failed: {}", e.getMessage());
        }
        try {
            detectStuck();
        } catch (RuntimeException e) {
            log.warn("Outbox stuck detection failed: {}", e.getMessage());
        }
        try {
            refreshGauges();
        } catch (RuntimeException e) {
            log.warn("Outbox gauge refresh failed: {}", e.getMessage());
        }
    }

    /**
     * BROADCAST rows the node no longer knows (dropped from the mempool) get their stored bytes sent
     * again - no re-sign, identical hash, so it can never create a second transaction. A fee bump (which
     * does change the hash) is deliberately left to the operator for rows that were ever seen.
     */
    void rebroadcastVanished() {
        Instant cutoff = Instant.now().minus(properties.getRebroadcastAfter());
        for (EvmSignedSubmission candidate : repository.findTop200ByStatusAndBroadcastAtBeforeOrderByBroadcastAtAsc(
                EvmSignedSubmission.Status.BROADCAST, cutoff)) {
            if (candidate.getLastRebroadcastAt() != null && candidate.getLastRebroadcastAt().isAfter(cutoff)) {
                continue;
            }
            try {
                isolated.run(() -> rebroadcastOne(candidate.getId()));
            } catch (RuntimeException e) {
                log.warn("Re-broadcast of outbox row {} failed: {}", candidate.getId(), e.getMessage());
            }
        }
    }

    /** Runs inside {@link IsolatedTransactionExecutor} (the row lock needs a transaction). */
    void rebroadcastOne(UUID id) {
        EvmSignedSubmission row = repository.findByIdForUpdate(id).orElse(null);
        if (row == null || row.getStatus() != EvmSignedSubmission.Status.BROADCAST) {
            return;
        }
        Optional<BlockchainTransaction> tx = transactions.findByTxHash(row.getTxHash());
        if (tx.isPresent() && tx.get().getStatus() != BlockchainTransaction.Status.PENDING
                && tx.get().getStatus() != BlockchainTransaction.Status.TIMEOUT) {
            return; // resolved
        }
        ChainConfig chain = chainConfigRepository.findById(row.getChainConfigId()).orElse(null);
        if (chain == null) return;
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        try {
            boolean mined = web3j.ethGetTransactionReceipt(row.getTxHash()).send().getTransactionReceipt().isPresent();
            boolean known = mined || web3j.ethGetTransactionByHash(row.getTxHash()).send().getTransaction().isPresent();
            if (known) {
                return; // in the pool or mined: the poller / stuck alert own it
            }
            BigInteger count = evmContractService.transactionCount(web3j, row.getSenderAddress(),
                    DefaultBlockParameterName.LATEST);
            if (count.compareTo(row.getNonce()) > 0) {
                return; // nonce consumed: the poller reconciles it (REPLACED / mined as replacement)
            }
        } catch (Exception e) {
            log.debug("Skipping re-broadcast of {}: chain state unavailable ({})", row.getTxHash(), e.getMessage());
            return;
        }
        row.setLastRebroadcastAt(Instant.now());
        row.setRebroadcastCount(row.getRebroadcastCount() + 1);
        try {
            evmContractService.broadcastPrepared(row.getChainConfigId(), web3j, row.getSignedPayload(), row.getTxHash());
            row.setLastError(null);
            row.setLastErrorClass(null);
            publish(new OutboxRecoveryEvent(row.getId(), "REBROADCAST", null, "SYSTEM", null, null,
                    Map.of("txHash", row.getTxHash(), "nonce", row.getNonce().toString(),
                            "sender", row.getSenderAddress(), "attempt", row.getRebroadcastCount())));
            meters.counter("registerwerk.outbox.rebroadcast", "result", "ok").increment();
        } catch (RuntimeException e) {
            row.setLastError(e.getMessage() != null && e.getMessage().length() > 2000
                    ? e.getMessage().substring(0, 2000) : e.getMessage());
            row.setLastErrorClass(BroadcastErrorClassifier.classify(e.getMessage()));
            meters.counter("registerwerk.outbox.rebroadcast", "result", "failed").increment();
        }
        repository.save(row);
    }

    /** One-time (per row) audit + WARN for rows without a receipt for longer than {@code stuck-after}. */
    void detectStuck() {
        for (EvmSignedSubmission row : repository.findAllStuck(Instant.now().minus(properties.getStuckAfter()))) {
            if (row.getStuckAlertedAt() != null) {
                continue;
            }
            isolated.run(() -> markStuckAlerted(row.getId()));
        }
    }

    void markStuckAlerted(UUID id) {
        EvmSignedSubmission row = repository.findByIdForUpdate(id).orElse(null);
        if (row == null || row.getStuckAlertedAt() != null) {
            return;
        }
        row.setStuckAlertedAt(Instant.now());
        repository.save(row);
        Map<String, Object> details = new HashMap<>();
        details.put("txHash", row.getTxHash());
        details.put("status", row.getStatus().name());
        details.put("nonce", row.getNonce().toString());
        details.put("sender", row.getSenderAddress());
        details.put("method", row.getMethodName());
        details.put("attempts", row.getAttemptCount());
        details.put("lastErrorClass", String.valueOf(row.getLastErrorClass()));
        details.put("regulatory", isRegulatory(row.getMethodName()));
        details.put("autoRepriceAllowed", properties.mayAutoReprice(row.getMethodName()));
        publish(new OutboxRecoveryEvent(row.getId(), "STUCK_DETECTED", null, "SYSTEM", null, null, details));
        log.error("EVM outbox row {} is stuck: {} nonce {} of {} ({}), status {}, last error class {}. "
                + "Operator action required (cancel / re-price) unless it is auto-repriceable.",
                row.getId(), row.getMethodName(), row.getNonce(), row.getSenderAddress(), row.getTxHash(),
                row.getStatus(), row.getLastErrorClass());
        meters.counter("registerwerk.outbox.stuck_detected", "regulatory",
                String.valueOf(isRegulatory(row.getMethodName()))).increment();
    }

    void refreshGauges() {
        Set<String> seen = new HashSet<>();
        Instant now = Instant.now();
        for (EvmSignedSubmissionRepository.SignerBacklog b : repository.preparedBacklogPerSigner()) {
            String key = b.getChainId().toPlainString() + "|" + b.getSenderAddress();
            seen.add(key);
            gauge(oldestAge, "registerwerk.outbox.oldest_prepared_age_seconds", b.getChainId().toPlainString(),
                    b.getSenderAddress(), key).set(Math.max(0, Duration.between(b.getOldestCreatedAt(), now).getSeconds()));
            gauge(preparedCount, "registerwerk.outbox.prepared_count", b.getChainId().toPlainString(),
                    b.getSenderAddress(), key).set(b.getPreparedCount());
        }
        // signers that drained since the last refresh must read 0, not their last value
        oldestAge.forEach((k, v) -> { if (!seen.contains(k)) v.set(0); });
        preparedCount.forEach((k, v) -> { if (!seen.contains(k)) v.set(0); });

        Map<String, Long> stuckPerSigner = new HashMap<>();
        for (EvmSignedSubmission row : repository.findAllStuck(now.minus(properties.getStuckAfter()))) {
            stuckPerSigner.merge(row.getChainId().toString() + "|" + row.getSenderAddress(), 1L, Long::sum);
        }
        stuckPerSigner.forEach((key, count) -> {
            String[] parts = key.split("\\|", 2);
            gauge(stuckCount, "registerwerk.outbox.stuck_count", parts[0], parts[1], key).set(count);
        });
        stuckCount.forEach((k, v) -> { if (!stuckPerSigner.containsKey(k)) v.set(0); });
    }

    private AtomicLong gauge(Map<String, AtomicLong> holders, String name, String chainId, String signer, String key) {
        return holders.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::doubleValue)
                    .tag("chain_id", chainId).tag("signer", signer).register(meters);
            return value;
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean isRegulatory(String methodName) {
        if (methodName == null) return false;
        String lower = methodName.toLowerCase(java.util.Locale.ROOT);
        return OutboxProperties.REGULATORY_PREFIXES.stream()
                .anyMatch(prefix -> lower.startsWith(prefix.toLowerCase(java.util.Locale.ROOT)));
    }

    private void publish(OutboxRecoveryEvent event) {
        events.publishEvent(event);
    }

    private OutboxEntry toEntry(EvmSignedSubmission row) {
        EvmContractService.PayloadInfo info = null;
        try {
            info = EvmContractService.describe(row.getSignedPayload());
        } catch (RuntimeException e) {
            log.debug("Could not decode payload of outbox row {}: {}", row.getId(), e.getMessage());
        }
        String txStatus = transactions.findByTxHash(row.getTxHash())
                .map(t -> t.getStatus().name()).orElse(null);
        Instant now = Instant.now();
        return new OutboxEntry(row.getId(), row.getChainConfigId(), row.getSenderAddress(), row.getNonce(),
                row.getTxHash(), row.getMethodName(), row.getContractAddress(), row.getStatus().name(),
                row.getKind().name(), row.getAttemptCount(), row.getLastError(),
                row.getLastErrorClass() != null ? row.getLastErrorClass().name() : null,
                row.getCreatedAt(), row.getFirstFailedAt(), row.getNextAttemptAt(), row.getBroadcastAt(),
                Math.max(0, Duration.between(row.getCreatedAt(), now).getSeconds()), row.getReplacesTxHash(),
                row.getSupersededByTxHash(), txStatus, isRegulatory(row.getMethodName()),
                properties.mayAutoReprice(row.getMethodName()),
                info != null ? info.maxFeePerGas() : null, info != null ? info.maxPriorityFeePerGas() : null,
                row.getRebroadcastCount());
    }
}
