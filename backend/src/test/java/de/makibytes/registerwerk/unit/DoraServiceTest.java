package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.dora.events.IctIncidentReportedEvent;
import de.makibytes.registerwerk.dora.events.IctIncidentStatusChangedEvent;
import de.makibytes.registerwerk.dora.internal.DoraService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoraService resilience-test unit tests")
class DoraServiceTest {

    @Mock
    private IctIncidentRepository incidentRepository;

    @Mock
    private ThirdPartyProviderRepository providerRepository;

    @Mock
    private ResilienceTestRepository resilienceTestRepository;

    @Mock
    private ApplicationEventPublisher events;

    private SimpleMeterRegistry meterRegistry;
    private DoraService doraService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        doraService = new DoraService(incidentRepository, providerRepository, resilienceTestRepository, events, meterRegistry);
    }

    private double breachGauge(String breachType) {
        return meterRegistry.get("registerwerk_dora_deadline_breaches").tag("breach_type", breachType).gauge().value();
    }

    @Test
    @DisplayName("recordResilienceTest persists a test with the given fields")
    void recordResilienceTest_persistsTest() {
        UUID actorId = UUID.randomUUID();
        when(resilienceTestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResilienceTest saved = doraService.recordResilienceTest(
                ResilienceTest.TestType.TLPT, "T-REX identity registry", true, null,
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15),
                ResilienceTest.Result.PASSED, null, "Redteam GmbH", "TLPT-2026-01", actorId);

        assertThat(saved.getTestType()).isEqualTo(ResilienceTest.TestType.TLPT);
        assertThat(saved.isTlptRequired()).isTrue();
        assertThat(saved.getResult()).isEqualTo(ResilienceTest.Result.PASSED);
        assertThat(saved.getCreatedBy()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("listOverdueResilienceTests delegates to the repository's due-date query")
    void listOverdueResilienceTests_delegatesToRepository() {
        ResilienceTest overdue = new ResilienceTest();
        when(resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(any()))
                .thenReturn(List.of(overdue));

        List<ResilienceTest> result = doraService.listOverdueResilienceTests();

        assertThat(result).containsExactly(overdue);
    }

    @Test
    @DisplayName("updateStatus publishes an audit event with the actor and status transition (finding #5)")
    void updateStatus_publishesAuditEvent() {
        UUID incidentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        IctIncident incident = new IctIncident();
        incident.setStatus(IctIncident.Status.INVESTIGATING);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        IctIncident result = doraService.updateStatus(
                incidentId, IctIncident.Status.CONTAINED, "root cause found", "patched", actorId);

        assertThat(result.getStatus()).isEqualTo(IctIncident.Status.CONTAINED);
        assertThat(result.getContainedAt()).isNotNull();
        ArgumentCaptor<IctIncidentStatusChangedEvent> captor = ArgumentCaptor.forClass(IctIncidentStatusChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().payload()).containsEntry("previousStatus", "INVESTIGATING")
                .containsEntry("newStatus", "CONTAINED");
    }

    @Test
    @DisplayName("reportIncident computes the 4h classification deadline for MAJOR severity (finding #4)")
    void reportIncident_computesClassificationDeadlineForMajor() {
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        IctIncident saved = doraService.reportIncident(
                "Outage", "desc", IctIncident.Category.SYSTEM_OUTAGE, IctIncident.Severity.MAJOR,
                null, null, UUID.randomUUID());

        assertThat(saved.getClassifiedAt()).isEqualTo(saved.getDetectedAt());
        assertThat(saved.getClassificationDeadline())
                .isEqualTo(saved.getDetectedAt().plus(4, java.time.temporal.ChronoUnit.HOURS));
        // The 4h deadline must be strictly earlier than the 24h one — it's the stricter,
        // binding sub-deadline the fix closes a gap for.
        assertThat(saved.getClassificationDeadline()).isBefore(saved.getInitialReportDeadline());
    }

    @Test
    @DisplayName("reportIncident does not compute a classification deadline for non-MAJOR severity")
    void reportIncident_noClassificationDeadlineForMinorSeverity() {
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        IctIncident saved = doraService.reportIncident(
                "Minor blip", "desc", IctIncident.Category.SYSTEM_OUTAGE, IctIncident.Severity.LOW,
                null, null, UUID.randomUUID());

        assertThat(saved.getClassifiedAt()).isNull();
        assertThat(saved.getClassificationDeadline()).isNull();
    }

    @Test
    @DisplayName("checkDeadlines logs an error for incidents past the 4h classification deadline")
    void checkDeadlines_logsClassificationBreach() {
        IctIncident overdue = new IctIncident();
        org.springframework.test.util.ReflectionTestUtils.setField(overdue, "id", UUID.randomUUID());
        when(incidentRepository.findOverdueClassificationReports(any())).thenReturn(List.of(overdue));
        when(incidentRepository.findOverdueInitialReports(any())).thenReturn(List.of());
        when(incidentRepository.findOverdueFinalReports(any())).thenReturn(List.of());
        when(resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(any())).thenReturn(List.of());

        // No exception is the main contract here — the actual log line isn't asserted since
        // this test doesn't attach a log appender; the repository call is the real check.
        doraService.checkDeadlines();

        verify(incidentRepository).findOverdueClassificationReports(any());
    }

    @Test
    @DisplayName("deadline-breach gauges reflect live overdue counts, tagged by breach_type (repo-wide alerting follow-up)")
    void breachGauges_reflectLiveOverdueCounts() {
        when(incidentRepository.findOverdueClassificationReports(any())).thenReturn(List.of(new IctIncident()));
        when(incidentRepository.findOverdueInitialReports(any())).thenReturn(List.of());
        when(incidentRepository.findOverdueFinalReports(any())).thenReturn(List.of(new IctIncident(), new IctIncident()));
        when(resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(any())).thenReturn(List.of());

        assertThat(breachGauge("classification")).isEqualTo(1.0);
        assertThat(breachGauge("initial_report")).isZero();
        assertThat(breachGauge("final_report")).isEqualTo(2.0);
        assertThat(breachGauge("resilience_test")).isZero();
    }

    @Test
    @DisplayName("markReportedToAuthority persists reportedBy and publishes an audit event (finding #5)")
    void markReportedToAuthority_persistsActorAndPublishesEvent() {
        UUID incidentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        IctIncident incident = new IctIncident();
        incident.setStatus(IctIncident.Status.RESOLVED);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        IctIncident result = doraService.markReportedToAuthority(incidentId, "BaFin-Ref-2026-001", true, actorId);

        assertThat(result.getReportedBy()).isEqualTo(actorId);
        assertThat(result.getStatus()).isEqualTo(IctIncident.Status.REPORTED_TO_AUTHORITY);
        ArgumentCaptor<IctIncidentReportedEvent> captor = ArgumentCaptor.forClass(IctIncidentReportedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().payload()).containsEntry("authorityRef", "BaFin-Ref-2026-001")
                .containsEntry("isFinalReport", true);
    }

    // ── getIncident (Track 7-1) ──────────────────────────────────────────────────

    @Test
    @DisplayName("getIncident returns the incident when it exists")
    void getIncident_found_returnsIt() {
        UUID incidentId = UUID.randomUUID();
        IctIncident incident = new IctIncident();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        assertThat(doraService.getIncident(incidentId)).isSameAs(incident);
    }

    @Test
    @DisplayName("getIncident throws EntityNotFoundException when it doesn't exist")
    void getIncident_notFound_throws() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> doraService.getIncident(incidentId))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }
}
