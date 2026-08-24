package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies the fail-closed contract of the screening gate (GwG §10):
 * approval must be blocked when an entity was never screened, when the latest
 * run is pending or errored, and when an unreviewed HIT exists — never only
 * in the HIT case.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningGateImpl fail-closed unit tests")
class ScreeningGateImplTest {

    @Mock
    private ScreeningRunRepository runRepository;

    @Mock
    private ScreeningHitRepository hitRepository;

    @Mock
    private ScreeningService screeningService;

    @Mock
    private SanctionsScreeningPort provider;

    private ScreeningGateImpl gate;

    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(provider.providerName()).thenReturn("provider-a");
        gate = new ScreeningGateImpl(runRepository, hitRepository, screeningService, List.of(provider));
    }

    private ScreeningRun run(ScreeningStatus status) {
        ScreeningRun r = new ScreeningRun();
        r.setEntityId(entityId);
        r.setProvider("provider-a");
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("never screened — blocks approval (fail closed)")
    void neverScreened_blocks() {
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(null);
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    @DisplayName("latest run ERROR — blocks approval (fail closed)")
    void errorRun_blocks() {
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(run(ScreeningStatus.ERROR));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    @DisplayName("latest run PENDING — blocks approval (fail closed)")
    void pendingRun_blocks() {
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(run(ScreeningStatus.PENDING));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    @DisplayName("latest run CLEAR — permits approval")
    void clearRun_permits() {
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(run(ScreeningStatus.CLEAR));
        assertThat(gate.hasUnresolvedHit(entityId)).isFalse();
    }

    @Test
    @DisplayName("HIT with unreviewed match — blocks approval")
    void hitWithUnreviewedMatch_blocks() {
        ScreeningRun hitRun = run(ScreeningStatus.HIT);
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(hitRun);
        when(hitRepository.findByRunIdAndAcceptedIsNull(hitRun.getId())).thenReturn(List.of(new ScreeningHit()));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    @DisplayName("HIT with all matches reviewed — permits approval")
    void hitAllReviewed_permits() {
        ScreeningRun hitRun = run(ScreeningStatus.HIT);
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(hitRun);
        when(hitRepository.findByRunIdAndAcceptedIsNull(hitRun.getId())).thenReturn(List.of());
        assertThat(gate.hasUnresolvedHit(entityId)).isFalse();
    }

    @Test
    @DisplayName("beneficial owner with ERROR run — blocks approval (fail closed)")
    void beneficialOwnerErrorRun_blocks() {
        UUID personId = UUID.randomUUID();
        when(runRepository.findNaturalPersonIdsByEntityLinkedRuns(entityId)).thenReturn(List.of(personId));
        ScreeningRun erroredRun = run(ScreeningStatus.ERROR);
        when(runRepository.findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(personId, "provider-a"))
                .thenReturn(erroredRun);
        assertThat(gate.hasUnresolvedBeneficialOwnerHit(entityId)).isTrue();
    }

    @Test
    @DisplayName("a clear result from one provider cannot hide another provider's error")
    void multipleProviders_oneError_blocks() {
        SanctionsScreeningPort providerB = org.mockito.Mockito.mock(SanctionsScreeningPort.class);
        when(providerB.providerName()).thenReturn("provider-b");
        gate = new ScreeningGateImpl(
                runRepository, hitRepository, screeningService, List.of(provider, providerB));
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-a"))
                .thenReturn(run(ScreeningStatus.CLEAR));
        ScreeningRun error = run(ScreeningStatus.ERROR);
        error.setProvider("provider-b");
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "provider-b"))
                .thenReturn(error);

        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }
}
