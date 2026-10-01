package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.NaturalPersonScreeningSubjectResolver;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningHitDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningResult;
import de.makibytes.registerwerk.screening.api.ScreeningProviderException;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.screening.events.ScreeningHitCarriedForwardEvent;
import de.makibytes.registerwerk.screening.events.ScreeningHitDetectedEvent;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 6 / K7: carry-forward (6-18), outage grace (6-18), CONFIRM_PEP (6-17). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScreeningControlsTest {

    @Mock SanctionsScreeningPort provider;
    @Mock ScreeningRunRepository runRepository;
    @Mock ScreeningHitRepository hitRepository;
    @Mock ApplicationEventPublisher events;
    @Mock LegalEntityRepository legalEntityRepository;
    @Mock NaturalPersonScreeningSubjectResolver resolver;

    private ScreeningService service;
    private ScreeningGateImpl gate;
    private final UUID entityId = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(provider.providerName()).thenReturn("P");
        when(runRepository.save(any(ScreeningRun.class))).thenAnswer(i -> i.getArgument(0));
        service = new ScreeningService(List.of(provider), runRepository, hitRepository, events,
                legalEntityRepository, new SimpleMeterRegistry(), resolver, ScreeningPolicy.defaults());
        gate = new ScreeningGateImpl(runRepository, hitRepository, service, List.of(provider),
                ScreeningPolicy.defaults(), new SimpleMeterRegistry());
    }

    private ScreeningHit accepted(String score, Duration age, boolean withApprover) {
        ScreeningHit h = new ScreeningHit();
        h.setMatchScore(new BigDecimal(score));
        h.setCategory(HitCategory.SANCTIONS);
        h.setAccepted(true);
        h.setResolution(HitResolution.FALSE_POSITIVE);
        h.setAcceptedBy(officer);
        h.setAcceptedAt(Instant.now().minus(age));
        if (withApprover) {
            h.setDualControlApproverId(approver);
        }
        return h;
    }

    private void rescreenReturns(double score, ScreeningHit source) {
        when(provider.screen(any())).thenReturn(new ScreeningResult(List.of(
                new ScreeningHitDto("EU", "name", "Acme Ltd", score, "id1", "SANCTIONS", "id1")), "v1",
                new BigDecimal("0.85")));
        when(hitRepository.findCarryForwardSources(eq(entityId), any(), eq(HitResolution.FALSE_POSITIVE), any(Pageable.class)))
                .thenReturn(source == null ? List.of() : List.of(source));
    }

    private ScreeningRun lastRun() {
        ArgumentCaptor<ScreeningRun> c = ArgumentCaptor.forClass(ScreeningRun.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    @Test
    void acceptedFalsePositiveIsCarriedForwardWithAudit() {
        rescreenReturns(0.88, accepted("0.87", Duration.ofDays(10), true));
        service.screenEntity(entityId, "Acme Ltd", "DE", null, ScreeningTrigger.PERIODIC_REFRESH);

        ArgumentCaptor<ScreeningHit> hit = ArgumentCaptor.forClass(ScreeningHit.class);
        verify(hitRepository).save(hit.capture());
        assertThat(hit.getValue().getAccepted()).isTrue();
        assertThat(hit.getValue().getCarriedFromHitId()).isEqualTo(null); // mock source has no id
        assertThat(hit.getValue().getCarriedAt()).isNotNull();
        assertThat(hit.getValue().getAcceptedBy()).isEqualTo(officer);
        assertThat(lastRun().getStatus()).isEqualTo(ScreeningStatus.ACCEPTED);
        assertThat(lastRun().getThresholdUsed()).isEqualByComparingTo("0.85");
        assertThat(lastRun().getDataVersion()).isEqualTo("v1");
        verify(events).publishEvent(any(ScreeningHitCarriedForwardEvent.class));
        verify(events, never()).publishEvent(any(ScreeningHitDetectedEvent.class));
    }

    @Test
    void scoreJumpExpiredWindowAndNoApproverStayOpen() {
        for (ScreeningHit source : List.of(
                accepted("0.87", Duration.ofDays(10), true),    // score jump below
                accepted("0.87", Duration.ofDays(91), true),    // expired
                accepted("0.87", Duration.ofDays(1), true))) {
            org.mockito.Mockito.clearInvocations(events, hitRepository);
            double score = source.getAcceptedAt().isAfter(Instant.now().minus(Duration.ofDays(2))) ? 0.87 : 0.88;
            if (source.getAcceptedAt().isBefore(Instant.now().minus(Duration.ofDays(5))) && source.getAcceptedAt().isAfter(Instant.now().minus(Duration.ofDays(30)))) {
                score = 0.93; // +0.06 > tolerance
            }
            rescreenReturns(score, source);
            if (score == 0.87) {
                source.setDualControlApproverId(null);
                // the repository query excludes decisions without approver: simulate by returning none
                rescreenReturns(score, null);
            }
            service.screenEntity(entityId, "Acme Ltd", "DE", null, ScreeningTrigger.PERIODIC_REFRESH);
            ArgumentCaptor<ScreeningHit> hit = ArgumentCaptor.forClass(ScreeningHit.class);
            verify(hitRepository).save(hit.capture());
            assertThat(hit.getValue().getAccepted()).isNull();
            verify(events).publishEvent(any(ScreeningHitDetectedEvent.class));
        }
    }

    @Test
    void outageKeepsLastClearWithinGraceAndBlocksAfter() {
        ScreeningRun good = run(ScreeningStatus.CLEAR, Instant.now().minus(Duration.ofHours(20)));
        ScreeningRun err = run(ScreeningStatus.ERROR, Instant.now().minus(Duration.ofHours(2)));
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "P")).thenReturn(err);
        when(runRepository.findByEntityIdAndProviderOrderByStartedAtDesc(eq(entityId), eq("P"), any(Pageable.class)))
                .thenReturn(List.of(err, good));
        assertThat(gate.hasUnresolvedHit(entityId)).isFalse();
        assertThat(gate.isRelyingOnStaleResult(entityId)).isTrue();

        // outage started 25 h ago -> grace over
        ScreeningRun oldErr = run(ScreeningStatus.ERROR, Instant.now().minus(Duration.ofHours(25)));
        ScreeningRun oldGood = run(ScreeningStatus.CLEAR, Instant.now().minus(Duration.ofHours(49)));
        when(runRepository.findByEntityIdAndProviderOrderByStartedAtDesc(eq(entityId), eq("P"), any(Pageable.class)))
                .thenReturn(List.of(err, oldErr, oldGood));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    void neverScreenedStaysBlockedDuringOutage() {
        ScreeningRun err = run(ScreeningStatus.ERROR, Instant.now().minus(Duration.ofHours(1)));
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "P")).thenReturn(err);
        when(runRepository.findByEntityIdAndProviderOrderByStartedAtDesc(eq(entityId), eq("P"), any(Pageable.class)))
                .thenReturn(List.of(err));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    void lastGoodHitWithOpenHitStillBlocksInGrace() {
        ScreeningRun hitRun = run(ScreeningStatus.HIT, Instant.now().minus(Duration.ofHours(5)));
        ScreeningRun err = run(ScreeningStatus.ERROR, Instant.now().minus(Duration.ofHours(1)));
        when(runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, "P")).thenReturn(err);
        when(runRepository.findByEntityIdAndProviderOrderByStartedAtDesc(eq(entityId), eq("P"), any(Pageable.class)))
                .thenReturn(List.of(err, hitRun));
        when(hitRepository.findByRunIdAndAcceptedIsNull(hitRun.getId())).thenReturn(List.of(new ScreeningHit()));
        assertThat(gate.hasUnresolvedHit(entityId)).isTrue();
    }

    @Test
    void providerErrorOnRescreenRecordsErrorRun() {
        when(provider.screen(any())).thenThrow(new ScreeningProviderException("P", "down"));
        service.screenEntity(entityId, "Acme Ltd", "DE", null, ScreeningTrigger.PERIODIC_REFRESH);
        assertThat(lastRun().getStatus()).isEqualTo(ScreeningStatus.ERROR);
    }

    @Test
    void confirmedPepStaysBlockingUntilEddAndSanctionsCannotBeConfirmed() {
        UUID hitId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        ScreeningHit hit = new ScreeningHit();
        hit.setCategory(HitCategory.PEP);
        hit.setRunId(UUID.randomUUID());
        when(hitRepository.findById(hitId)).thenReturn(Optional.of(hit));
        when(hitRepository.save(any(ScreeningHit.class))).thenAnswer(i -> i.getArgument(0));
        ScreeningRun r = new ScreeningRun();
        r.setNaturalPersonId(personId);
        when(runRepository.findById(hit.getRunId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.confirmPep(hitId, officer, "COMPLIANCE_OFFICER", null, "n"))
                .hasMessageContaining("second approver");
        ScreeningHit out = service.confirmPep(hitId, officer, "COMPLIANCE_OFFICER", approver, "confirmed via register");
        assertThat(out.getAccepted()).isNull();
        assertThat(out.getResolution()).isEqualTo(HitResolution.CONFIRMED_PEP);
        assertThat(out.blocksGate(Instant.now())).isTrue();
        out.setEddApprovedAt(Instant.now());
        out.setEddReviewDue(Instant.now().plus(Duration.ofDays(30)));
        assertThat(out.blocksGate(Instant.now())).isFalse();
        out.setEddReviewDue(Instant.now().minusSeconds(5));
        assertThat(out.blocksGate(Instant.now())).isTrue();
        // cannot now be accepted as a false positive
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, "X", approver, "r"))
                .isInstanceOf(InvalidStateTransitionException.class);

        ScreeningHit sanction = new ScreeningHit();
        sanction.setCategory(HitCategory.SANCTIONS);
        when(hitRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))).thenReturn(Optional.of(sanction));
        assertThatThrownBy(() -> service.confirmPep(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                officer, "X", approver, "n")).isInstanceOf(InvalidStateTransitionException.class);
    }

    private ScreeningRun run(ScreeningStatus status, Instant at) {
        ScreeningRun r = new ScreeningRun();
        r.setStatus(status);
        r.setStartedAt(at);
        r.setProvider("P");
        r.setEntityId(entityId);
        try {
            var f = ScreeningRun.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(r, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return r;
    }
}
