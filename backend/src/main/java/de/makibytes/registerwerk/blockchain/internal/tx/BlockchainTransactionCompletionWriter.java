package de.makibytes.registerwerk.blockchain.internal.tx;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists {@link BlockchainTransaction} lifecycle outcomes inside a fresh, auditable
 * transaction — mirrors {@code AssetDeploymentCompletionWriter}, and exists for the same reason:
 * {@link de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService#pollPendingTransactions()}
 * previously called {@code complete}/{@code markTimeout} via {@code this.} (self-invocation),
 * which silently bypasses the Spring AOP proxy and makes {@code @Transactional} on those methods
 * inert. Splitting the writes into a separate injected bean means the caller goes through the
 * proxy and the transaction boundary is real.
 */
@Component
public class BlockchainTransactionCompletionWriter {

    private static final Logger log = LoggerFactory.getLogger(BlockchainTransactionCompletionWriter.class);

    private final BlockchainTransactionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final ChainEffectRecorder chainEffectRecorder;

    public BlockchainTransactionCompletionWriter(
            BlockchainTransactionRepository repository, ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry, ChainEffectRecorder chainEffectRecorder) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.chainEffectRecorder = chainEffectRecorder;
    }

    @Transactional
    public void complete(BlockchainTransaction tx, TransactionReceipt receipt) {
        boolean success = "0x1".equals(receipt.getStatus());
        tx.setStatus(success ? BlockchainTransaction.Status.SUCCESS : BlockchainTransaction.Status.FAILED);
        Instant completedAt = Instant.now();
        tx.setCompletedAt(completedAt);
        if (receipt.getGasUsed() != null) tx.setGasUsed(receipt.getGasUsed().longValue());
        if (receipt.getBlockNumber() != null) tx.setBlockNumber(receipt.getBlockNumber().longValue());
        if (receipt.getBlockHash() != null) tx.setBlockHash(receipt.getBlockHash());
        if (!success) tx.setErrorMessage("Transaction reverted on-chain");
        repository.save(tx);

        recordSubmissionToConfirmationLatency(tx, completedAt);
        publishCompletionAuditEvent(tx);
        recordChainEffectIfCompensable(tx);
        log.info("Blockchain tx={} completed status={}", tx.getTxHash(), tx.getStatus());
    }

    /**
     * Journals a {@code TX_COMPLETED} chain effect so an automatically compensable routine
     * retraction can revert the transaction to PENDING. A typed finality violation is quarantined
     * without automatic mutation, but the exact effect remains available for operator review.
     * Only SUCCESS is journalled — FAILED/TIMEOUT already represent "nothing happened", so there is
     * nothing to undo if that block is later retracted. Silently skipped (not an error) when
     * {@code chainConfigId}/{@code blockNumber} couldn't be resolved (see
     * {@link BlockchainTransaction#getChainConfigId()}'s javadoc) — this transaction simply won't be
     * A SUCCESS without chain/block provenance is rejected so the surrounding transaction rolls
     * back; committing a terminal business assertion with no compensable source would be a
     * fail-open correctness bug.
     */
    private void recordChainEffectIfCompensable(BlockchainTransaction tx) {
        if (tx.getStatus() != BlockchainTransaction.Status.SUCCESS) {
            return;
        }
        if (tx.getChainConfigId() == null || tx.getBlockNumber() == null
                || tx.getBlockHash() == null || tx.getBlockHash().isBlank()) {
            throw new IllegalStateException(
                    "Cannot complete a successful blockchain transaction without chain and block provenance");
        }
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                tx.getChainConfigId(), tx.getBlockNumber(), tx.getBlockHash(), tx.getTxHash(),
                "blockchain", BlockchainTxRevertCompensator.EFFECT_TYPE, "BlockchainTransaction", tx.getId(),
                tx.getAssetId(), CompensationCategory.INVERSE_FLIP));
    }

    @Transactional
    public void markTimeout(BlockchainTransaction tx, long timeoutSeconds) {
        tx.setStatus(BlockchainTransaction.Status.TIMEOUT);
        Instant completedAt = Instant.now();
        tx.setCompletedAt(completedAt);
        tx.setErrorMessage("Transaction not mined within " + timeoutSeconds + "s");
        repository.save(tx);

        recordSubmissionToConfirmationLatency(tx, completedAt);
        publishCompletionAuditEvent(tx);
        log.warn("Blockchain tx={} timed out", tx.getTxHash());
    }

    /**
     * time from {@code createdAt} (submission — see
     * {@code BlockchainTransactionService.record}) to whichever terminal status this tx reached,
     * tagged by chain and outcome. Previously invisible except by manually diffing
     * {@code created_at}/{@code completed_at} in the database.
     */
    private void recordSubmissionToConfirmationLatency(BlockchainTransaction tx, Instant completedAt) {
        Timer.builder("registerwerk.blockchain.tx.confirmation.latency")
                .description("Time from a blockchain_transaction row's submission to its terminal status")
                .tag("chain", tx.getChain() != null ? tx.getChain() : "UNKNOWN")
                .tag("outcome", tx.getStatus().name())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.between(tx.getCreatedAt(), completedAt));
    }

    /**
     * Records a receipt seen for the first time (or whose block moved) while still below the
     * confirmation depth, so the next poll has a baseline block hash to re-verify against before
     * a reorg has had a chance to un-mine it.
     */
    @Transactional
    public void recordProvisionalReceipt(BlockchainTransaction tx, TransactionReceipt receipt) {
        if (receipt.getBlockNumber() != null) tx.setBlockNumber(receipt.getBlockNumber().longValue());
        if (receipt.getBlockHash() != null) tx.setBlockHash(receipt.getBlockHash());
        repository.save(tx);
    }

    /**
     * A previously-recorded block hash for this (still-PENDING) transaction no longer matches
     * what the chain reports at that height — its block was reorged out. Reset to PENDING (not
     * FAILED: the transaction may simply need to be re-mined) and clear the stale block info so
     * the next poll starts fresh. Deliberately does <em>not</em> attempt resubmission: no nonce
     * or unsigned calldata is captured at submission time (see
     * {@link de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService#review}), so
     * safe resubmit machinery remains deliberately unimplemented (see
     * {@link de.makibytes.registerwerk.blockchain.internal.NonceCoordinator}'s Javadoc) — this
     * method only prevents the register from asserting a confirmation count built on a block
     * that no longer exists.
     */
    @Transactional
    public void resetToPendingAfterReorg(BlockchainTransaction tx, String newBlockHashObserved) {
        log.warn("Blockchain tx={} block hash mismatch (was {}, chain now reports {} at that height) — "
                        + "resetting to PENDING; awaiting re-mining or operator action.",
                tx.getTxHash(), tx.getBlockHash(), newBlockHashObserved);
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        tx.setBlockHash(null);
        tx.setBlockNumber(null);
        repository.save(tx);
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

        eventPublisher.publishEvent(new BlockchainTxStatusEvent(
                tx.getDeploymentId(), null, tx.getActorRole(), tx.getStatus().name(), payload));
    }
}
