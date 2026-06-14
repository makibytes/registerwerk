package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fail-closed screening gate (GwG §10 Abs. 1 Nr. 5, §11).
 *
 * <p>An approval-blocking condition exists not only when the latest run produced an
 * unresolved HIT, but also when the entity has never been screened, the latest run
 * is still PENDING, errored, or resulted in rejection. A screening that did not
 * complete successfully must never be treated as a clear result.
 */
@Component
class ScreeningGateImpl implements ScreeningGate {

    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;

    ScreeningGateImpl(ScreeningRunRepository runRepository,
                      ScreeningHitRepository hitRepository) {
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
    }

    @Override
    public boolean hasUnresolvedHit(UUID entityId) {
        ScreeningRun latest = runRepository.findTopByEntityIdOrderByStartedAtDesc(entityId);
        return blocksApproval(latest);
    }

    @Override
    public boolean hasUnresolvedBeneficialOwnerHit(UUID entityId) {
        List<UUID> personIds = runRepository.findNaturalPersonIdsByEntityLinkedRuns(entityId);
        for (UUID personId : personIds) {
            ScreeningRun latest = runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(personId);
            if (blocksApproval(latest)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fail closed: only a completed CLEAR/ACCEPTED run, or a HIT run whose hits have
     * all been reviewed, permits approval.
     */
    private boolean blocksApproval(ScreeningRun latest) {
        if (latest == null) {
            return true; // never screened — block until a screening run completes
        }
        return switch (latest.getStatus()) {
            case CLEAR, ACCEPTED -> false;
            case HIT -> !hitRepository.findByRunIdAndAcceptedIsNull(latest.getId()).isEmpty();
            case PENDING, ERROR, REJECTED -> true;
        };
    }
}
