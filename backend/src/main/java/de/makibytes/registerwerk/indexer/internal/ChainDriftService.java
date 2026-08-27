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
            // Unfiltered by confirmation on purpose: no UI passes assetId today, so there is no
            // "Open" work-queue expectation to protect here. Apply the same confirmed-gate as
            // below if this ever becomes an operator-facing per-asset view.
            return repository.findByAssetIdOrderByDetectedAtDesc(assetId, pageable);
        }
        // A same-run "candidate" (not yet confirmed on a second detection pass) is not a decided
        // case yet — RESOLVED already reflects a real outcome either way (human close, or the
        // job's own auto-clear of a never-confirmed candidate), so only OPEN needs the gate.
        return status == ChainDriftStatus.OPEN
                ? repository.findByStatusAndConfirmedTrueOrderByDetectedAtDesc(status, pageable)
                : repository.findByStatusOrderByDetectedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public ChainDriftEvent get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ChainDriftEvent", id));
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return repository.countByStatusAndConfirmedTrue(ChainDriftStatus.OPEN);
    }

    /**
     * Closes a drift case with a mandatory explanation of how it was resolved — a registry
     * correction, an on-chain correction, or a documented reason the divergence is expected
     * (e.g. a since-reconciled indexer lag). This is the only path to RESOLVED for a
     * <em>confirmed</em> case, and it is not reversible from here — a fresh divergence on the
     * same wallet opens a new case rather than reopening this one, matching the job's own dedup
     * rule (one OPEN event per deployment+wallet). The one exception is the detection job's own
     * narrow auto-resolve of a same-run "candidate" that disappears before ever being confirmed —
     * that case was never surfaced to a human in the first place, so it needs no human decision.
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
