package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The demo repair must unblock approved demo parties via the real gate, never an unresolved-HIT one. */
@DisplayName("ScreeningDemoClearRunRepair + real ScreeningGateImpl")
class ScreeningDemoClearRunRepairTest {

    private static final String PROVIDER = "OPEN_SANCTIONS";

    private final List<ScreeningRun> runs = new ArrayList<>();
    private final ScreeningRunRepository runRepository = mock(ScreeningRunRepository.class);
    private final ScreeningHitRepository hitRepository = mock(ScreeningHitRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final SanctionsScreeningPort provider = mock(SanctionsScreeningPort.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID hitOwner = UUID.randomUUID();

    private LegalEntity approved;
    private LegalEntity hitEntity;
    private LegalEntity pending;
    private ScreeningDemoClearRunRepair repair;
    private ScreeningGateImpl gate;

    private LegalEntity entity(String number, KycStatus kyc) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityNumber(number);
        e.setKycStatus(kyc);
        return e;
    }

    @BeforeEach
    void setUp() {
        when(provider.providerName()).thenReturn(PROVIDER);
        approved = entity("DEMO-NI-001", KycStatus.APPROVED);
        hitEntity = entity("DEMO-AF-001", KycStatus.APPROVED);
        pending = entity("DEMO-XX-001", KycStatus.NOT_STARTED);
        when(entities.findAll()).thenReturn(List.of(approved, hitEntity, pending));

        when(runRepository.save(any(ScreeningRun.class))).thenAnswer(inv -> {
            ScreeningRun r = inv.getArgument(0);
            runs.add(r);
            return r;
        });
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(any(), anyString())).thenAnswer(inv ->
                runs.stream().filter(r -> inv.getArgument(0).equals(r.getEntityId())
                        && inv.getArgument(1).equals(r.getProvider()))
                        .max(Comparator.comparing(ScreeningRun::getStartedAt)).orElse(null));
        when(runRepository.findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(any(), anyString())).thenAnswer(inv ->
                runs.stream().filter(r -> inv.getArgument(0).equals(r.getNaturalPersonId())
                        && inv.getArgument(1).equals(r.getProvider()))
                        .max(Comparator.comparing(ScreeningRun::getStartedAt)).orElse(null));
        when(runRepository.findCurrentBeneficialOwnerPersonIds(approved.getId())).thenReturn(List.of(owner));
        when(runRepository.findCurrentBeneficialOwnerPersonIds(hitEntity.getId())).thenReturn(List.of(hitOwner));
        when(runRepository.findNaturalPersonIdsByEntityLinkedRuns(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            UUID p = id.equals(approved.getId()) ? owner : id.equals(hitEntity.getId()) ? hitOwner : null;
            return p == null ? List.of() : List.of(p);
        });

        // Pre-existing deliberate unresolved HIT demo case (what ScreeningDemoDataSeeder creates).
        ScreeningRun hit = new ScreeningRun();
        hit.setEntityId(hitEntity.getId());
        hit.setProvider(PROVIDER);
        hit.setStatus(ScreeningStatus.HIT);
        hit.setStartedAt(Instant.now().minusSeconds(3600));
        runs.add(hit);
        ScreeningHit open = new ScreeningHit();
        when(hitRepository.findByRunIdAndAcceptedIsNull(hit.getId())).thenReturn(List.of(open));
        // a UUID-less (non-persisted) run id is null: stub for null too
        when(hitRepository.findByRunIdAndAcceptedIsNull(null)).thenReturn(List.of(open));

        repair = new ScreeningDemoClearRunRepair(entities, runRepository, List.of(provider));
        gate = new ScreeningGateImpl(runRepository, hitRepository, mock(ScreeningService.class), List.of(provider));
    }

    @Test
    @DisplayName("blocked before, passes after; HIT demo entity and non-approved entity stay untouched")
    void repairUnblocksApprovedButNotHit() {
        assertThat(gate.hasUnresolvedHit(approved.getId())).isTrue(); // never screened -> blocked

        repair.run(null);

        assertThat(gate.hasUnresolvedHit(approved.getId())).isFalse();
        assertThat(gate.hasUnresolvedBeneficialOwnerHit(approved.getId())).isFalse();
        ScreeningRun clear = runs.stream().filter(r -> approved.getId().equals(r.getEntityId())).findFirst().orElseThrow();
        assertThat(clear.getStatus()).isEqualTo(ScreeningStatus.CLEAR);
        assertThat(clear.getCompletedAt()).isNotNull();
        assertThat(clear.getListsChecked()).isNotEmpty();
        assertThat(clear.getInitiatedBy()).isNull();

        assertThat(gate.hasUnresolvedHit(hitEntity.getId())).isTrue();
        assertThat(runs.stream().filter(r -> hitEntity.getId().equals(r.getEntityId()))).hasSize(1);
        assertThat(gate.hasUnresolvedHit(pending.getId())).isTrue();
    }

    @Test
    @DisplayName("idempotent: second run adds nothing")
    void idempotent() {
        repair.run(null);
        int after = runs.size();
        repair.run(null);
        assertThat(runs).hasSize(after);
    }

    @Test
    @DisplayName("periodic refresh is skipped on a demo-seeded stack so it cannot append ERROR runs")
    void periodicRefreshSkippedWhenDemoSeeded() {
        ScreeningService service = mock(ScreeningService.class);
        new ScreeningRefreshJob(runRepository, service, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), true)
                .periodicRefresh();
        org.mockito.Mockito.verifyNoInteractions(service);
        org.mockito.Mockito.verify(runRepository, org.mockito.Mockito.never()).findDistinctActiveEntityIds();
    }
}
