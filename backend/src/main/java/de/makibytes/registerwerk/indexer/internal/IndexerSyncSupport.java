package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Transaction plumbing for the polling indexers (P4B-6). A sync pass runs in one transaction; if it
 * fails (a database error such as {@code numeric field overflow} aborts the PostgreSQL
 * transaction) the failure bookkeeping — {@code consecutive_errors}, {@code last_error}, the
 * {@code ERROR} state — must still be written, so it is recorded in a fresh transaction
 * <em>after</em> the sync transaction has rolled back. Recording it inside the aborted
 * transaction lost it, and a poisoned batch left the indexer {@code ACTIVE} with a stale cursor
 * forever.
 */
@Component
class IndexerSyncSupport {

    private static final Logger log = LoggerFactory.getLogger(IndexerSyncSupport.class);

    private final IndexerStateRepository indexerStateRepository;
    private final TransactionTemplate syncTx;
    private final TransactionTemplate bookkeepingTx;

    IndexerSyncSupport(PlatformTransactionManager transactionManager, IndexerStateRepository indexerStateRepository) {
        this.indexerStateRepository = indexerStateRepository;
        this.syncTx = new TransactionTemplate(transactionManager);
        this.bookkeepingTx = new TransactionTemplate(transactionManager);
        this.bookkeepingTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs {@code work} in one transaction; any exception rolls it back and propagates. */
    void inTransaction(Runnable work) {
        syncTx.executeWithoutResult(status -> work.run());
    }

    /** Counts a failed pass in its own transaction and flips the indexer to ERROR at {@code maxErrors}. */
    void recordFailure(UUID chainConfigId, String chainIdentifier, IndexerState.IndexerType type,
                       Exception failure, int maxErrors) {
        try {
            bookkeepingTx.executeWithoutResult(status -> {
                IndexerState state = indexerStateRepository.findByChainConfigIdAndIndexerType(chainConfigId, type)
                        .orElseGet(() -> {
                            IndexerState s = new IndexerState();
                            s.setChainConfigId(chainConfigId);
                            s.setIndexerType(type);
                            s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                            return s;
                        });
                int errors = state.getConsecutiveErrors() + 1;
                state.setConsecutiveErrors(errors);
                state.setLastError(truncate(failure.getMessage(), 2000));
                if (errors >= maxErrors) {
                    state.setStatus(IndexerState.IndexerStatus.ERROR);
                    log.error("Chain {}: indexer {} set to ERROR after {} consecutive failures. Last error: {}",
                            chainIdentifier, type, errors, failure.getMessage());
                } else {
                    log.warn("Chain {}: {} sync error ({}/{}): {}",
                            chainIdentifier, type, errors, maxErrors, failure.getMessage());
                }
                indexerStateRepository.save(state);
            });
        } catch (Exception bookkeeping) {
            log.error("Chain {}: could not record {} indexer failure ({}): {}", chainIdentifier, type,
                    failure.getMessage(), bookkeeping.getMessage(), bookkeeping);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
