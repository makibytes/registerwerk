package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncidentAlert;
import de.makibytes.registerwerk.dora.api.IctIncidentAlertRepository;
import de.makibytes.registerwerk.dora.api.IctIncidentReport;
import de.makibytes.registerwerk.dora.api.IctIncidentReportRepository;
import de.makibytes.registerwerk.dora.events.IctIncidentClassifiedEvent;
import de.makibytes.registerwerk.dora.events.IctIncidentDeadlineBreachedEvent;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Mock
    private IctIncidentReportRepository reportRepository;

    @Mock
    private IctIncidentAlertRepository alertRepository;

    @Mock
    private DualControlGate dualControlGate;

    private SimpleMeterRegistry meterRegistry;
    private DoraService doraService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        doraService = new DoraService(incidentRepository, providerRepository, resilienceTestRepository, events, meterRegistry,
                reportRepository, alertRepository, dualControlGate);
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
    @DisplayName("updateStatus publishes an audit event with the actor and status transition")
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

    private IctIncident incident(IctIncident.Severity severity, IctIncident.Status status, Instant awareness) {
        IctIncident i = new IctIncident();
        org.springframework.test.util.ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
        i.setCategory(IctIncident.Category.SYSTEM_OUTAGE);
        i.setTitle("t");
        i.setSeverity(severity);
        i.setStatus(status);
        i.setAwarenessAt(awareness);
        i.setDetectedAt(awareness);
        i.setInitialReportDeadline(awareness.plus(24, ChronoUnit.HOURS));
        i.setFinalReportDeadline(awareness.plus(30, ChronoUnit.DAYS));
        return i;
    }

    private void stubFind(IctIncident i) {
        org.mockito.Mockito.lenient().when(incidentRepository.findById(i.getId())).thenReturn(Optional.of(i));
        org.mockito.Mockito.lenient().when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("reportIncident anchors the 24h/1-month clocks on awareness and the 4h clock on classification (MAJOR)")
    void reportIncident_majorDeadlinesFromAwareness() {
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant awareness = Instant.now().minus(3, ChronoUnit.HOURS);

        IctIncident saved = doraService.reportIncident(
                "Outage", "desc", IctIncident.Category.SYSTEM_OUTAGE, IctIncident.Severity.MAJOR,
                awareness, "Critical service unavailable > 2h", Map.of("clientsAffected", "all"),
                null, null, UUID.randomUUID());

        assertThat(saved.getAwarenessAt()).isEqualTo(awareness);
        assertThat(saved.getClassifiedAt()).isNotNull();
        assertThat(saved.getClassificationDeadline()).isEqualTo(saved.getClassifiedAt().plus(4, ChronoUnit.HOURS));
        assertThat(saved.getInitialReportDeadline()).isEqualTo(awareness.plus(24, ChronoUnit.HOURS));
        assertThat(saved.getFinalReportDeadline()).isEqualTo(awareness.plus(30, ChronoUnit.DAYS));
        assertThat(saved.getClassificationReason()).isEqualTo("Critical service unavailable > 2h");
    }

    @Test
    @DisplayName("reportIncident computes 24h / 1-month deadlines for every incident but no 4h clock below MAJOR")
    void reportIncident_nonMajorStillGetsAnchorDeadlines() {
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        IctIncident saved = doraService.reportIncident(
                "Minor blip", "desc", IctIncident.Category.SYSTEM_OUTAGE, IctIncident.Severity.LOW,
                null, null, null, null, null, UUID.randomUUID());

        assertThat(saved.getClassifiedAt()).isNull();
        assertThat(saved.getClassificationDeadline()).isNull();
        assertThat(saved.getInitialReportDeadline()).isEqualTo(saved.getAwarenessAt().plus(24, ChronoUnit.HOURS));
        assertThat(saved.getFinalReportDeadline()).isEqualTo(saved.getAwarenessAt().plus(30, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("reportIncident refuses a MAJOR without reason and a future awareness time")
    void reportIncident_validation() {
        assertThatThrownBy(() -> doraService.reportIncident("x", "d", IctIncident.Category.OTHER,
                IctIncident.Severity.MAJOR, null, " ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> doraService.reportIncident("x", "d", IctIncident.Category.OTHER,
                IctIncident.Severity.LOW, Instant.now().plus(1, ChronoUnit.HOURS), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("classify HIGH -> MAJOR starts the 4h clock now, keeps awareness-based clocks, clears pending, audits before/after")
    void classify_escalationToMajor() {
        IctIncident i = incident(IctIncident.Severity.HIGH, IctIncident.Status.INVESTIGATING,
                Instant.now().minus(30, ChronoUnit.HOURS));
        i.setClassificationPending(true);
        stubFind(i);
        UUID actor = UUID.randomUUID();

        IctIncident r = doraService.classify(i.getId(), IctIncident.Severity.MAJOR, "RTS threshold: duration", Map.of("duration", ">24h"), actor);

        assertThat(r.getSeverity()).isEqualTo(IctIncident.Severity.MAJOR);
        assertThat(r.getClassifiedAt()).isNotNull();
        assertThat(r.getClassificationDeadline()).isEqualTo(r.getClassifiedAt().plus(4, ChronoUnit.HOURS));
        // awareness 30h ago: the 24h clock is already past, so the incident is immediately overdue
        assertThat(r.getInitialReportDeadline()).isBefore(Instant.now());
        assertThat(r.isClassificationPending()).isFalse();
        ArgumentCaptor<IctIncidentClassifiedEvent> c = ArgumentCaptor.forClass(IctIncidentClassifiedEvent.class);
        verify(events).publishEvent(c.capture());
        assertThat(c.getValue().payload()).containsEntry("previousSeverity", "HIGH").containsEntry("newSeverity", "MAJOR")
                .containsEntry("reason", "RTS threshold: duration");
        verify(dualControlGate, never()).require(any());
    }

    @Test
    @DisplayName("classify needs a reason; withdrawing MAJOR needs a second approver and leaves a marker, status untouched")
    void classify_reasonAndDowngrade() {
        IctIncident i = incident(IctIncident.Severity.MAJOR, IctIncident.Status.INVESTIGATING, Instant.now().minus(1, ChronoUnit.HOURS));
        i.setClassifiedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        stubFind(i);
        assertThatThrownBy(() -> doraService.classify(i.getId(), IctIncident.Severity.LOW, " ", null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        UUID approver = UUID.randomUUID();
        when(dualControlGate.require("DORA_INCIDENT_DOWNGRADE")).thenReturn(approver);
        IctIncident r = doraService.classify(i.getId(), IctIncident.Severity.LOW, "Not a major incident per RTS", null, UUID.randomUUID());

        assertThat(r.getDowngradedAt()).isNotNull();
        assertThat(r.getDowngradeReason()).isEqualTo("Not a major incident per RTS");
        assertThat(r.getStatus()).isEqualTo(IctIncident.Status.INVESTIGATING);
        ArgumentCaptor<IctIncidentClassifiedEvent> c = ArgumentCaptor.forClass(IctIncidentClassifiedEvent.class);
        verify(events).publishEvent(c.capture());
        assertThat(c.getValue().dualControlApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("updateStatus cannot set REPORTED_TO_AUTHORITY, and cannot leave CLOSED")
    void updateStatus_stateMachine() {
        IctIncident i = incident(IctIncident.Severity.LOW, IctIncident.Status.INVESTIGATING, Instant.now());
        stubFind(i);
        assertThatThrownBy(() -> doraService.updateStatus(i.getId(), IctIncident.Status.REPORTED_TO_AUTHORITY, null, null, UUID.randomUUID()))
                .isInstanceOf(InvalidStateTransitionException.class);

        IctIncident closed = incident(IctIncident.Severity.LOW, IctIncident.Status.CLOSED, Instant.now());
        stubFind(closed);
        assertThatThrownBy(() -> doraService.updateStatus(closed.getId(), IctIncident.Status.INVESTIGATING, null, null, UUID.randomUUID()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("closing a MAJOR incident needs initial AND final report; with both it needs the second approver")
    void updateStatus_closeMajorRequiresReportsAndApprover() {
        IctIncident i = incident(IctIncident.Severity.MAJOR, IctIncident.Status.RESOLVED, Instant.now().minus(2, ChronoUnit.DAYS));
        stubFind(i);
        assertThatThrownBy(() -> doraService.updateStatus(i.getId(), IctIncident.Status.CLOSED, null, null, UUID.randomUUID()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("INITIAL_REPORT_MISSING").hasMessageContaining("FINAL_REPORT_MISSING");
        verify(dualControlGate, never()).require(any());

        i.setInitialReportedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        i.setFinalReportedAt(Instant.now());
        IctIncident r = doraService.updateStatus(i.getId(), IctIncident.Status.CLOSED, null, null, UUID.randomUUID());
        assertThat(r.getStatus()).isEqualTo(IctIncident.Status.CLOSED);
        verify(dualControlGate).require("DORA_INCIDENT_CLOSE");
    }

    @Test
    @DisplayName("a draft (classification pending) cannot be closed before a person classifies it")
    void updateStatus_pendingDraftCannotClose() {
        IctIncident i = incident(IctIncident.Severity.HIGH, IctIncident.Status.INVESTIGATING, Instant.now());
        i.setClassificationPending(true);
        stubFind(i);
        assertThatThrownBy(() -> doraService.updateStatus(i.getId(), IctIncident.Status.CLOSED, null, null, UUID.randomUUID()))
                .hasMessageContaining("CLASSIFICATION_PENDING");
    }

    @Test
    @DisplayName("markReportedToAuthority is append-only: a second initial report adds a row but never overwrites timestamp/ref")
    void markReported_writeOnce() {
        IctIncident i = incident(IctIncident.Severity.MAJOR, IctIncident.Status.INVESTIGATING, Instant.now().minus(2, ChronoUnit.HOURS));
        stubFind(i);
        UUID actor = UUID.randomUUID();

        doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.INITIAL, "REF-1", null, null, actor);
        Instant first = i.getInitialReportedAt();
        assertThat(first).isNotNull();
        assertThat(i.getAuthorityRef()).isEqualTo("REF-1");
        assertThat(i.getIntermediateReportDeadline()).isEqualTo(first.plus(72, ChronoUnit.HOURS));

        doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.INITIAL, "REF-2", "correction", null, actor);

        assertThat(i.getInitialReportedAt()).isEqualTo(first);
        assertThat(i.getAuthorityRef()).isEqualTo("REF-1");
        verify(reportRepository, org.mockito.Mockito.times(2)).save(any(IctIncidentReport.class));
    }

    @Test
    @DisplayName("intermediate/final reports need an initial one; every report needs an authority reference and a MAJOR incident")
    void markReported_preconditions() {
        IctIncident i = incident(IctIncident.Severity.MAJOR, IctIncident.Status.INVESTIGATING, Instant.now().minus(2, ChronoUnit.HOURS));
        stubFind(i);
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.FINAL, "R", null, null, actor))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.INITIAL, " ", null, null, actor))
                .isInstanceOf(IllegalArgumentException.class);
        IctIncident low = incident(IctIncident.Severity.LOW, IctIncident.Status.INVESTIGATING, Instant.now());
        stubFind(low);
        assertThatThrownBy(() -> doraService.markReportedToAuthority(low.getId(), IctIncidentReport.Type.INITIAL, "R", null, null, actor))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("final report moves a not-yet-closed incident to REPORTED_TO_AUTHORITY and tracks intermediate separately")
    void markReported_finalAndIntermediate() {
        IctIncident i = incident(IctIncident.Severity.MAJOR, IctIncident.Status.RESOLVED, Instant.now().minus(2, ChronoUnit.HOURS));
        i.setInitialReportedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        stubFind(i);
        UUID actor = UUID.randomUUID();
        doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.INTERMEDIATE, "R2", null, null, actor);
        assertThat(i.getIntermediateReportedAt()).isNotNull();
        assertThat(i.getFinalReportedAt()).isNull();
        doraService.markReportedToAuthority(i.getId(), IctIncidentReport.Type.FINAL, "R3", null, null, actor);
        assertThat(i.getFinalReportedAt()).isNotNull();
        assertThat(i.getStatus()).isEqualTo(IctIncident.Status.REPORTED_TO_AUTHORITY);
    }

    @Test
    @DisplayName("checkDeadlines alerts once per (incident, breach) and not again within the re-alert interval")
    void checkDeadlines_alertsOnce() {
        IctIncident overdue = incident(IctIncident.Severity.MAJOR, IctIncident.Status.CLOSED, Instant.now().minus(3, ChronoUnit.DAYS));
        when(incidentRepository.findOverdueClassificationReports(any())).thenReturn(List.of());
        when(incidentRepository.findOverdueInitialReports(any())).thenReturn(List.of(overdue));
        when(incidentRepository.findOverdueIntermediateReports(any())).thenReturn(List.of());
        when(incidentRepository.findOverdueFinalReports(any())).thenReturn(List.of());
        when(resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(any())).thenReturn(List.of());
        when(alertRepository.findByIncidentIdAndBreachType(overdue.getId(), "initial_report")).thenReturn(Optional.empty());

        doraService.checkDeadlines();
        verify(events).publishEvent(any(IctIncidentDeadlineBreachedEvent.class));

        IctIncidentAlert recent = new IctIncidentAlert(overdue.getId(), "initial_report");
        when(alertRepository.findByIncidentIdAndBreachType(overdue.getId(), "initial_report")).thenReturn(Optional.of(recent));
        org.mockito.Mockito.clearInvocations(events);
        doraService.checkDeadlines();
        verify(events, never()).publishEvent(any(IctIncidentDeadlineBreachedEvent.class));

        recent.setAlertedAt(Instant.now().minus(7, ChronoUnit.HOURS));
        doraService.checkDeadlines();
        verify(events).publishEvent(any(IctIncidentDeadlineBreachedEvent.class));
    }

    @Test
    @DisplayName("openDraftIncident opens an unclassified draft once per source type")
    void openDraft_dedup() {
        when(incidentRepository.findFirstBySourceEventTypeAndClassificationPendingTrueAndStatusNot(eq("X"), any()))
                .thenReturn(Optional.empty());
        when(incidentRepository.save(any(IctIncident.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID ref = UUID.randomUUID();

        Optional<IctIncident> draft = doraService.openDraftIncident("t", "d", IctIncident.Category.OTHER, "X", ref);

        assertThat(draft).isPresent();
        assertThat(draft.get().isClassificationPending()).isTrue();
        assertThat(draft.get().getClassifiedAt()).isNull();
        assertThat(draft.get().getSourceEventRef()).isEqualTo(ref);

        when(incidentRepository.findFirstBySourceEventTypeAndClassificationPendingTrueAndStatusNot(eq("X"), any()))
                .thenReturn(draft);
        assertThat(doraService.openDraftIncident("t", "d", IctIncident.Category.OTHER, "X", ref)).isEmpty();
    }

    @Test
    @DisplayName("deadline-breach gauges reflect live overdue counts, tagged by breach_type (alerting metrics)")
    void breachGauges_reflectLiveOverdueCounts() {
        when(incidentRepository.findOverdueClassificationReports(any())).thenReturn(List.of(new IctIncident()));
        when(incidentRepository.findOverdueInitialReports(any())).thenReturn(List.of());
        when(incidentRepository.findOverdueIntermediateReports(any())).thenReturn(List.of(new IctIncident()));
        when(incidentRepository.findOverdueFinalReports(any())).thenReturn(List.of(new IctIncident(), new IctIncident()));
        when(resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(any())).thenReturn(List.of());

        assertThat(breachGauge("classification")).isEqualTo(1.0);
        assertThat(breachGauge("initial_report")).isZero();
        assertThat(breachGauge("intermediate_report")).isEqualTo(1.0);
        assertThat(breachGauge("final_report")).isEqualTo(2.0);
        assertThat(breachGauge("resilience_test")).isZero();
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
