package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.events.ChainDriftResolvedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class ChainDriftService {

    private final ChainDriftEventRepository repository;
    private final ApplicationEventPublisher events;

    public ChainDriftService(ChainDriftEventRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<ChainDriftEvent> list(ChainDriftStatus status, UUID assetId, Pageable pageable) {
        if (assetId != null) {
            return repository.findByAssetIdOrderByDetectedAtDesc(assetId, pageable);
        }
        return repository.findByStatusOrderByDetectedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public ChainDriftEvent get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ChainDriftEvent", id));
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return repository.countByStatus(ChainDriftStatus.OPEN);
    }

    /**
     * Closes a drift case with a mandatory explanation of how it was resolved — a registry
     * correction, an on-chain correction, or a documented reason the divergence is expected
     * (e.g. a since-reconciled indexer lag). The detection job only ever inserts/refreshes OPEN
     * rows; this is the sole path to RESOLVED, and it is not reversible from here — a fresh
     * divergence on the same wallet opens a new case rather than reopening this one, matching
     * the job's own dedup rule (one OPEN event per deployment+wallet).
     */
    public ChainDriftEvent resolve(UUID id, UUID actorId, String actorRole, String notes) {
        ChainDriftEvent event = get(id);
        if (event.getStatus() != ChainDriftStatus.OPEN) {
            throw new InvalidStateTransitionException(
                    "ChainDriftEvent", event.getStatus().name(), "RESOLVED");
        }
        event.setStatus(ChainDriftStatus.RESOLVED);
        event.setResolvedAt(Instant.now());
        event.setResolvedBy(actorId);
        event.setResolutionNotes(notes);
        ChainDriftEvent saved = repository.save(event);

        events.publishEvent(new ChainDriftResolvedEvent(id, actorId, actorRole, Map.of(
                "assetId", event.getAssetId(),
                "walletAddress", event.getWalletAddress(),
                "severity", event.getSeverity().name(),
                "dbBalance", event.getDbBalance(),
                "onchainBalance", event.getOnchainBalance(),
                "resolutionNotes", notes
        )));
        return saved;
    }
}
