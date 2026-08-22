package de.makibytes.registerwerk.blockchain.internal.tx;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code TX_COMPLETED} — undoes a {@link BlockchainTransaction}
 * marked SUCCESS whose block was later retracted. Discovered by {@code CompensationDispatcher} via
 * {@link ChainEffectCompensator} collection injection (see its javadoc for why this needs no
 * import of {@code finality.internal}).
 *
 * <p>Journalled proactively at completion time (see
 * {@link BlockchainTransactionCompletionWriter#recordChainEffectIfCompensable}), unlike the
 * {@code indexer} module's {@code HolderRecomputeCompensator} which discovers its affected rows
 * reactively at retraction time — {@code blockchain_transaction} completion is a single,
 * well-defined write-time event, so there is a natural moment to journal it.
 *
 * <p>Deliberately talks to {@link BlockchainTransactionRepository} directly rather than going
 * through {@link BlockchainTransactionCompletionWriter}: that writer depends on
 * {@code ChainEffectRecorder} (to journal {@code TX_COMPLETED} in the first place), whose
 * implementation transitively depends on every registered {@code ChainEffectCompensator} —
 * including this one. Routing the reversal through the writer closes that loop into a genuine
 * Spring circular-bean-dependency failure at startup (caught the hard way: {@code mvn verify}
 * failed context refresh, not a Modulith cycle, since both classes share this package). No
 * `@Transactional` needed here either — the caller, {@code CompensationDispatcher.compensate},
 * already runs in one.
 */
@Component
class BlockchainTxRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "TX_COMPLETED";

    private static final Logger log = LoggerFactory.getLogger(BlockchainTxRevertCompensator.class);

    private final BlockchainTransactionRepository repository;

    BlockchainTxRevertCompensator(BlockchainTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID txId = effect.entityId();
        BlockchainTransaction tx = repository.findById(txId).orElse(null);
        if (tx == null) {
            return new CompensationOutcome.NotApplicable("BlockchainTransaction " + txId + " no longer exists");
        }
        if (tx.getStatus() != BlockchainTransaction.Status.SUCCESS) {
            // Already reverted by a prior compensation attempt, or moved on by some other path —
            // compensators must be idempotent, so this is not an error.
            return new CompensationOutcome.NotApplicable(
                    "BlockchainTransaction " + txId + " is no longer SUCCESS (status=" + tx.getStatus() + ")");
        }

        log.error("Blockchain tx={} was SUCCESS at block={} but that block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification. Compensation runs at any reorg depth; "
                        + "if this retraction reached an already-FINALIZED block, that is additionally a "
                        + "consensus failure deeper than the configured confirmation policy guarantees.",
                tx.getTxHash(), tx.getBlockNumber());
        tx.setStatus(BlockchainTransaction.Status.PENDING);
        tx.setBlockHash(null);
        tx.setBlockNumber(null);
        tx.setCompletedAt(null);
        tx.setGasUsed(null);
        tx.setErrorMessage(null);
        repository.save(tx);

        return new CompensationOutcome.Compensated(
                "Reverted blockchain_transaction " + txId + " (txHash=" + tx.getTxHash() + ") to PENDING after retraction");
    }
}
