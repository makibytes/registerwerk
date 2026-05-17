package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.events.IndexerStaleEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduled monitor that periodically checks the health of all registered indexers and
 * raises warnings (and audit events) for those that are stale or in an error state.
 */
@Service
public class IndexerMonitorService {

    private static final Logger log = LoggerFactory.getLogger(IndexerMonitorService.class);

    /** An indexer is considered stale if it has not synced within this window. */
    private static final Duration STALE_THRESHOLD = Duration.ofHours(2);

    private final IndexerStateRepository indexerStateRepository;
    private final ApplicationEventPublisher eventPublisher;

    public IndexerMonitorService(
            IndexerStateRepository indexerStateRepository,
            ApplicationEventPublisher eventPublisher) {
        this.indexerStateRepository = indexerStateRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs every 5 minutes. Identifies indexers that are either:
     * <ul>
     *   <li>In {@code ERROR} status, or</li>
     *   <li>Have not synced for more than {@value #STALE_THRESHOLD} hours.</li>
     * </ul>
     * Each problem indexer is logged at WARN level and triggers an {@code INDEXER_STALE} audit event.
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional(readOnly = true)
    public void checkIndexerHealth() {
        try {
            Instant staleThreshold = Instant.now().minus(STALE_THRESHOLD);

            // Collect error-state indexers.
            List<IndexerState> problematic = new ArrayList<>(
                    indexerStateRepository.findByStatus(IndexerState.IndexerStatus.ERROR));

            // Collect active indexers that have not synced recently.
            List<IndexerState> stale = indexerStateRepository.findByStatusAndLastSyncedAtBefore(
                    IndexerState.IndexerStatus.ACTIVE, staleThreshold);
            problematic.addAll(stale);

            if (problematic.isEmpty()) {
                log.debug("IndexerMonitor: all indexers are healthy.");
                return;
            }

            for (IndexerState state : problematic) {
                String reason = state.getStatus() == IndexerState.IndexerStatus.ERROR
                        ? "status=ERROR, consecutiveErrors=" + state.getConsecutiveErrors()
                                + ", lastError=" + state.getLastError()
                        : "STALE — lastSyncedAt=" + state.getLastSyncedAt()
                                + " (threshold=" + staleThreshold + ")";

                log.warn("Indexer ALERT: chainConfigId={}, type={}, {}",
                        state.getChainConfigId(), state.getIndexerType(), reason);

                eventPublisher.publishEvent(new IndexerStaleEvent(state.getId(), null, null, state.getIndexerType().name(), state.getConsecutiveErrors()));
            }

            log.warn("IndexerMonitor: {} indexer(s) require attention.", problematic.size());
        } catch (Exception e) {
            log.error("Unexpected error in IndexerMonitorService.checkIndexerHealth: {}",
                    e.getMessage(), e);
        }
    }
}
