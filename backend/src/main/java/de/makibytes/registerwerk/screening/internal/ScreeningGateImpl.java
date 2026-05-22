package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

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
        if (latest == null || latest.getStatus() != ScreeningStatus.HIT) return false;
        return !hitRepository.findByRunIdAndAcceptedIsNull(latest.getId()).isEmpty();
    }

    @Override
    public boolean hasUnresolvedBeneficialOwnerHit(UUID entityId) {
        List<UUID> personIds = runRepository.findNaturalPersonIdsByEntityLinkedRuns(entityId);
        for (UUID personId : personIds) {
            ScreeningRun latest = runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(personId);
            if (latest != null && latest.getStatus() == ScreeningStatus.HIT
                    && !hitRepository.findByRunIdAndAcceptedIsNull(latest.getId()).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
