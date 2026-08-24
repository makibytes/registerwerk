package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.events.IndexerResetEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Operator recovery surface for a stuck indexer.
 *
 * <p>Every sync service refuses to run once {@code consecutive_errors} reaches its threshold
 * (10 for most chains, 5 for Canton — see each service's {@code MAX_CONSECUTIVE_ERRORS}) and
 * stays in {@code ERROR} status indefinitely — there was previously no path back to
 * {@code ACTIVE} other than a direct SQL {@code UPDATE indexer_state}
 * (see {@code docs/operator/indexers/resilience.md}'s "Manual recovery" section, which documented
 * exactly that SQL because no application-level alternative existed).
 */
@Service
@Transactional
public class IndexerAdminService {

    private static final Logger log = LoggerFactory.getLogger(IndexerAdminService.class);

    private final IndexerStateRepository repository;
    private final ApplicationEventPublisher events;

    public IndexerAdminService(IndexerStateRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Clears the error state so the indexer resumes on its next scheduled tick — no restart
     * required.
     *
     * @param fullResync when {@code false} (the default), only the error state is cleared and the
     *                   existing cursor ({@code last_synced_block}/{@code last_final_block}/
     *                   {@code last_synced_signature}) is kept, so the next tick resumes exactly
     *                   where it left off. When {@code true}, the cursor is also cleared, forcing
     *                   a full re-sync from genesis — only appropriate when the existing cursor
     *                   itself is suspected to be wrong (e.g. after a manual chain-state
     *                   correction), since it re-processes the chain's entire history for this
     *                   indexer and is far more expensive than a plain error-clear.
     */
    public IndexerState reset(UUID indexerStateId, UUID actorId, String actorRole, boolean fullResync) {
        IndexerState state = repository.findById(indexerStateId)
                .orElseThrow(() -> new EntityNotFoundException("IndexerState", indexerStateId));

        state.setStatus(IndexerState.IndexerStatus.ACTIVE);
        state.setConsecutiveErrors(0);
        state.setLastError(null);
        if (fullResync) {
            state.setLastSyncedBlock(null);
            state.setLastFinalBlock(null);
            state.setLastSyncedSignature(null);
        }
        IndexerState saved = repository.save(state);

        events.publishEvent(new IndexerResetEvent(
                indexerStateId, actorId, actorRole, state.getIndexerType().name(), fullResync));
        log.info("Indexer reset: id={} chainConfigId={} type={} fullResync={} actor={}",
                indexerStateId, state.getChainConfigId(), state.getIndexerType(), fullResync, actorId);
        return saved;
    }
}
