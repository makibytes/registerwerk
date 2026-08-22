package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.events.ChainEffectAcknowledgedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin operations on the effect journal — called by {@code finality.web.FinalityJournalController}
 * (same module, different subpackage, mirroring {@code FinalityPolicyAdminService}'s equivalent
 * relationship to {@code FinalityPolicyController}). Backs the operator "unresolved compensation"
 * queue: every {@code chain_effect} row that failed or was escalated as irreversible blocks the
 * affected asset via {@code FinalityGateImpl} until acknowledged here.
 */
@Service
public class FinalityJournalAdminService {

    private static final List<ChainEffect.Status> UNRESOLVED_STATUSES =
            List.of(ChainEffect.Status.COMPENSATION_FAILED, ChainEffect.Status.IRREVERSIBLE_ESCALATED);

    private final ChainEffectRepository repository;
    private final CompensationDispatcher dispatcher;
    private final ApplicationEventPublisher eventPublisher;

    FinalityJournalAdminService(ChainEffectRepository repository, CompensationDispatcher dispatcher,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<ChainEffectView> listUnresolved() {
        return repository.findByStatusInOrderByRecordedAtDesc(UNRESOLVED_STATUSES).stream()
                .map(ChainEffectView::of).toList();
    }

    /** Re-runs the registered {@code ChainEffectCompensator} for one row — the manual "retry now"
     *  action for a {@code COMPENSATION_FAILED} row (the automatic retry job already does this on
     *  its own schedule; this is for an operator who doesn't want to wait, e.g. right after fixing
     *  whatever made the compensator fail). A no-op, safely, if the row isn't currently claimable
     *  (already resolved, or another dispatcher run is in flight) — see {@code
     *  CompensationDispatcher.compensate}'s javadoc. */
    @Transactional
    public CompensationOutcome retry(UUID chainEffectId) {
        if (!repository.existsById(chainEffectId)) {
            throw new EntityNotFoundException("ChainEffect", chainEffectId);
        }
        return dispatcher.compensate(chainEffectId);
    }

    /**
     * Records that an admin has reviewed an unresolved effect and accepts proceeding despite it —
     * the action that lifts {@code FinalityGateImpl}'s per-asset freeze. Does not change {@code
     * status}: the compensation genuinely failed or is irreversible, which stays true; only the
     * gate's willingness to keep blocking on it is lifted.
     *
     * @param reason mandatory, audited justification — same convention as {@code
     *               FinalityPolicyAdminService.createOverride}'s override reason
     * @throws IllegalArgumentException if the row is not currently in an unresolved status (there
     *         is nothing to acknowledge on a row that already compensated cleanly, or that was
     *         never a failure in the first place)
     */
    @Transactional
    public ChainEffectView acknowledge(UUID chainEffectId, String reason, UUID actorId, String actorRole) {
        ChainEffect row = repository.findById(chainEffectId)
                .orElseThrow(() -> new EntityNotFoundException("ChainEffect", chainEffectId));
        if (!UNRESOLVED_STATUSES.contains(row.getStatus())) {
            throw new IllegalArgumentException(
                    "ChainEffect " + chainEffectId + " is " + row.getStatus() + ", not an unresolved "
                            + "compensation — nothing to acknowledge");
        }
        row.setAcknowledgedBy(actorId);
        row.setAcknowledgedAt(Instant.now());
        row.setAcknowledgeReason(reason);
        ChainEffect saved = repository.save(row);

        eventPublisher.publishEvent(new ChainEffectAcknowledgedEvent(
                saved.getId(), saved.getEffectType(), saved.getEntityType(), saved.getEntityId(),
                saved.getAssetId(), reason, actorId, actorRole, Instant.now()));
        return ChainEffectView.of(saved);
    }
}
