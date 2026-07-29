package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Verifies the four-eyes controls on screening-hit acceptance:
 * mandatory reason, mandatory second approver for high-score hits,
 * and rejection of self-approval.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningService.acceptHit dual-control unit tests")
class ScreeningServiceAcceptHitTest {

    @Mock
    private ScreeningRunRepository runRepository;

    @Mock
    private ScreeningHitRepository hitRepository;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private LegalEntityRepository legalEntityRepository;

    private ScreeningService service;
    private SimpleMeterRegistry meterRegistry;

    private final UUID hitId = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ScreeningService(
                List.<SanctionsScreeningPort>of(), runRepository, hitRepository, events, legalEntityRepository,
                meterRegistry);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private ScreeningHit hitWithScore(String score) {
        ScreeningHit hit = new ScreeningHit();
        hit.setMatchScore(new BigDecimal(score));
        when(hitRepository.findById(hitId)).thenReturn(Optional.of(hit));
        return hit;
    }

    @Test
    @DisplayName("blank reason is rejected (GwG §8 documentation duty)")
    void blankReason_rejected() {
        hitWithScore("0.50");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, "COMPLIANCE_OFFICER", null, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is mandatory");
    }

    @Test
    @DisplayName("high-score hit without second approver is rejected (four-eyes)")
    void highScoreWithoutApprover_rejected() {
        hitWithScore("0.92");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, "COMPLIANCE_OFFICER", null, "false positive"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dual control required");
    }

    @Test
    @DisplayName("self-approval is rejected — approver must differ from officer")
    void selfApproval_rejected() {
        hitWithScore("0.92");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, "COMPLIANCE_OFFICER", officer, "false positive"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different user");
    }

    @Test
    @DisplayName("low-score hit can be accepted by a single officer with reason")
    void lowScoreSingleOfficer_accepted() {
        ScreeningHit hit = hitWithScore("0.50");
        when(hitRepository.save(any(ScreeningHit.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreeningHit accepted = service.acceptHit(hitId, officer, "COMPLIANCE_OFFICER", null, "name collision, different DOB");

        assertThat(accepted.getAccepted()).isTrue();
        assertThat(accepted.getAcceptedBy()).isEqualTo(officer);
        assertThat(hit.getDualControlApproverId()).isNull();
    }

    @Test
    @DisplayName("high-score hit with distinct second approver is accepted and recorded")
    void highScoreWithApprover_accepted() {
        ScreeningHit hit = hitWithScore("0.95");
        when(hitRepository.save(any(ScreeningHit.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreeningHit accepted = service.acceptHit(hitId, officer, "COMPLIANCE_OFFICER", approver, "verified different entity via register");

        assertThat(accepted.getAccepted()).isTrue();
        assertThat(accepted.getDualControlApproverId()).isEqualTo(approver);
        assertThat(accepted.getDualControlApprovedAt()).isNotNull();

        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.screening.events.SanctionsHitAcceptedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(de.makibytes.registerwerk.screening.events.SanctionsHitAcceptedEvent.class);
        org.mockito.Mockito.verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("errors-recent gauge reflects a live count of ERROR-status runs (repo-wide alerting follow-up)")
    void errorsRecentGauge_reflectsLiveCount() {
        when(runRepository.countByStatusAndStartedAtAfter(org.mockito.ArgumentMatchers.eq(ScreeningStatus.ERROR), any()))
                .thenReturn(3L);

        assertThat(gauge("registerwerk_screening_errors_recent_total")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("periodicRefresh() tracks succeeded/failed separately and updates the failures gauge")
    void periodicRefresh_tracksSucceededAndFailedSeparately() {
        UUID okEntity = UUID.randomUUID();
        UUID failingEntity = UUID.randomUUID();
        when(runRepository.findDistinctActiveEntityIds()).thenReturn(List.of(okEntity, failingEntity));

        LegalEntity okLegalEntity = new LegalEntity();
        okLegalEntity.setId(okEntity);
        okLegalEntity.setCurrentName("OK Entity GmbH");
        LegalEntity failingLegalEntity = new LegalEntity();
        failingLegalEntity.setId(failingEntity);
        failingLegalEntity.setCurrentName("Failing Entity GmbH");
        when(legalEntityRepository.findById(okEntity)).thenReturn(Optional.of(okLegalEntity));
        when(legalEntityRepository.findById(failingEntity)).thenReturn(Optional.of(failingLegalEntity));

        SanctionsScreeningPort provider = org.mockito.Mockito.mock(SanctionsScreeningPort.class);
        when(provider.providerName()).thenReturn("test-provider");
        when(provider.screenEntity(any(ScreeningSubjectDto.class))).thenReturn(List.of());
        // Own registry, not the shared field from setUp(): registering a second same-named gauge
        // against one registry doesn't rebind it to this instance's AtomicInteger — the first
        // registration (from setUp()'s unused `service`) wins and this instance's updates
        // wouldn't be visible through it.
        SimpleMeterRegistry ownRegistry = new SimpleMeterRegistry();
        ScreeningService serviceWithProvider = new ScreeningService(
                List.of(provider), runRepository, hitRepository, events, legalEntityRepository, ownRegistry);

        // The very first save() call inside screenEntity() (before its own try/catch) throws for
        // failingEntity's run — screenEntity() itself never catches provider-level orchestration
        // failures like this, so it propagates up to periodicRefresh()'s own catch.
        doAnswer(inv -> {
            ScreeningRun run = inv.getArgument(0);
            if (run.getEntityId().equals(failingEntity)) {
                throw new RuntimeException("DB save failed");
            }
            return run;
        }).when(runRepository).save(any(ScreeningRun.class));

        serviceWithProvider.periodicRefresh();

        assertThat(ownRegistry.get("registerwerk_screening_periodic_refresh_last_failures").gauge().value()).isEqualTo(1.0);
    }
}
