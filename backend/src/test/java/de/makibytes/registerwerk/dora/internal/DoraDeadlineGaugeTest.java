package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncident.Category;
import de.makibytes.registerwerk.dora.api.IctIncident.Severity;
import de.makibytes.registerwerk.dora.api.IctIncidentAlertRepository;
import de.makibytes.registerwerk.dora.api.IctIncidentReportRepository;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@code registerwerk_dora_deadline_due_seconds} (lead time to the next open DORA deadline per
 * breach type, 7B-06) and its sibling {@code registerwerk_dora_deadline_breaches}, read through a
 * {@link SimpleMeterRegistry}. The incident repository is an in-memory stand-in whose queries mirror
 * the JPQL in {@link IctIncidentRepository} (MAJOR, not downgraded, report not yet filed, deadline not
 * yet passed), so the real {@link DoraService} entry / classification / deadline code drives the gauge.
 * The JPQL itself is exercised against PostgreSQL by DoraIncidentControlsIT.
 */
@DisplayName("DORA deadline gauges (registerwerk_dora_deadline_due_seconds / _breaches)")
class DoraDeadlineGaugeTest {

    private static final String DUE = "registerwerk_dora_deadline_due_seconds";
    private static final String BREACHES = "registerwerk_dora_deadline_breaches";
    private static final double TOLERANCE_S = 10.0;

    private final List<IctIncident> store = new ArrayList<>();
    private MeterRegistry registry;
    private DoraService service;

    @BeforeEach
    void setUp() {
        IctIncidentRepository incidents = mock(IctIncidentRepository.class);
        lenient().when(incidents.save(any())).thenAnswer(inv -> {
            IctIncident i = inv.getArgument(0);
            if (i.getId() == null) ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
            if (!store.contains(i)) store.add(i);
            return i;
        });
        lenient().when(incidents.findById(any())).thenAnswer(inv ->
                store.stream().filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());

        // mirrors IctIncidentRepository.next*Deadline: MIN(deadline) of open MAJOR incidents, deadline >= now
        lenient().when(incidents.nextClassificationDeadline(any())).thenAnswer(inv ->
                next(inv.getArgument(0), IctIncident::getClassificationDeadline, IctIncident::getInitialReportedAt));
        lenient().when(incidents.nextInitialReportDeadline(any())).thenAnswer(inv ->
                next(inv.getArgument(0), IctIncident::getInitialReportDeadline, IctIncident::getInitialReportedAt));
        lenient().when(incidents.nextIntermediateReportDeadline(any())).thenAnswer(inv ->
                next(inv.getArgument(0), IctIncident::getIntermediateReportDeadline, IctIncident::getIntermediateReportedAt));
        lenient().when(incidents.nextFinalReportDeadline(any())).thenAnswer(inv ->
                next(inv.getArgument(0), IctIncident::getFinalReportDeadline, IctIncident::getFinalReportedAt));
        // mirrors find*Overdue*: same predicate with deadline < now
        lenient().when(incidents.findOverdueClassificationReports(any())).thenAnswer(inv ->
                overdue(inv.getArgument(0), IctIncident::getClassificationDeadline, IctIncident::getInitialReportedAt));
        lenient().when(incidents.findOverdueInitialReports(any())).thenAnswer(inv ->
                overdue(inv.getArgument(0), IctIncident::getInitialReportDeadline, IctIncident::getInitialReportedAt));
        lenient().when(incidents.findOverdueIntermediateReports(any())).thenAnswer(inv ->
                overdue(inv.getArgument(0), IctIncident::getIntermediateReportDeadline, IctIncident::getIntermediateReportedAt));
        lenient().when(incidents.findOverdueFinalReports(any())).thenAnswer(inv ->
                overdue(inv.getArgument(0), IctIncident::getFinalReportDeadline, IctIncident::getFinalReportedAt));

        ResilienceTestRepository tests = mock(ResilienceTestRepository.class);
        lenient().when(tests.findByNextDueDateBeforeOrderByNextDueDateAsc(any())).thenReturn(List.of());
        DualControlGate gate = mock(DualControlGate.class);
        lenient().when(gate.require(any())).thenReturn(UUID.randomUUID());

        registry = new SimpleMeterRegistry();
        service = new DoraService(incidents, mock(ThirdPartyProviderRepository.class), tests,
                mock(ApplicationEventPublisher.class), registry, mock(IctIncidentReportRepository.class),
                mock(IctIncidentAlertRepository.class), gate);
    }

    private Instant next(Instant now, Function<IctIncident, Instant> deadline, Function<IctIncident, Instant> reportedAt) {
        return store.stream()
                .filter(i -> i.getSeverity() == Severity.MAJOR && i.getDowngradedAt() == null
                        && reportedAt.apply(i) == null && deadline.apply(i) != null
                        && !deadline.apply(i).isBefore(now))
                .map(deadline).min(Comparator.naturalOrder()).orElse(null);
    }

    private List<IctIncident> overdue(Instant now, Function<IctIncident, Instant> deadline, Function<IctIncident, Instant> reportedAt) {
        return store.stream()
                .filter(i -> i.getSeverity() == Severity.MAJOR && i.getDowngradedAt() == null
                        && reportedAt.apply(i) == null && deadline.apply(i) != null && deadline.apply(i).isBefore(now))
                .toList();
    }

    private double due(String breachType) {
        return registry.get(DUE).tag("breach_type", breachType).gauge().value();
    }

    private double breaches(String breachType) {
        return registry.get(BREACHES).tag("breach_type", breachType).gauge().value();
    }

    private IctIncident reportMajor(Instant awareness) {
        return service.reportIncident("Outage", "ledger down", Category.SYSTEM_OUTAGE, Severity.MAJOR, awareness,
                "Critical function unavailable", Map.of("clientsAffected", true), null, null, UUID.randomUUID());
    }

    @Test
    @DisplayName("no open incidents: every breach type reads NaN (nothing to warn about) and no breach counts")
    void noOpenIncidentsIsNaN() {
        for (String type : List.of("classification", "initial_report", "intermediate_report", "final_report")) {
            assertThat(due(type)).as(type).isNaN();
        }
        assertThat(breaches("initial_report")).isZero();

        // a MINOR-severity incident has no regulatory deadline to count down
        service.reportIncident("Blip", "x", Category.OTHER, Severity.LOW, null, null, null, null, null, UUID.randomUUID());
        assertThat(due("initial_report")).isNaN();
        assertThat(due("final_report")).isNaN();
    }

    @Test
    @DisplayName("one MAJOR incident: seconds to the 4 h classification, 24 h initial and 30 d final deadlines; intermediate waits for the initial report")
    void majorIncidentCountsDownToItsDeadlines() {
        reportMajor(Instant.now().minus(Duration.ofHours(2)));

        assertThat(due("classification")).isCloseTo(Duration.ofHours(4).toSeconds(), within(TOLERANCE_S));
        assertThat(due("initial_report")).isCloseTo(Duration.ofHours(22).toSeconds(), within(TOLERANCE_S));
        assertThat(due("final_report")).isCloseTo(Duration.ofDays(30).minusHours(2).toSeconds(), within(TOLERANCE_S));
        assertThat(due("intermediate_report")).as("72 h clock starts with the initial notification").isNaN();
        assertThat(breaches("initial_report")).isZero();
    }

    @Test
    @DisplayName("the gauge reports the nearest deadline across incidents")
    void nearestDeadlineWins() {
        reportMajor(Instant.now().minus(Duration.ofHours(2)));
        reportMajor(Instant.now().minus(Duration.ofHours(20)));

        assertThat(due("initial_report")).isCloseTo(Duration.ofHours(4).toSeconds(), within(TOLERANCE_S));
    }

    @Test
    @DisplayName("a breached deadline leaves the lead-time gauge (NaN) and shows up in the breach gauge instead")
    void breachedDeadlineMovesToTheBreachGauge() {
        IctIncident incident = reportMajor(Instant.now().minus(Duration.ofHours(25)));
        incident.setClassificationDeadline(Instant.now().minus(Duration.ofHours(1)));

        assertThat(due("initial_report")).as("24 h deadline passed an hour ago").isNaN();
        assertThat(due("classification")).isNaN();
        assertThat(breaches("initial_report")).isEqualTo(1.0);
        assertThat(breaches("classification")).isEqualTo(1.0);
        assertThat(due("final_report")).as("the 30 d deadline is still ahead")
                .isCloseTo(Duration.ofDays(30).minusHours(25).toSeconds(), within(TOLERANCE_S));
        assertThat(breaches("final_report")).isZero();
    }

    @Test
    @DisplayName("filing the initial report ends that clock and starts the 72 h intermediate clock")
    void initialReportStartsTheIntermediateClock() {
        IctIncident incident = reportMajor(Instant.now().minus(Duration.ofHours(2)));
        incident.setInitialReportedAt(Instant.now());
        DoraService.computeDeadlines(incident);

        assertThat(due("initial_report")).isNaN();
        assertThat(due("classification")).as("classification clock ends with the initial report").isNaN();
        assertThat(due("intermediate_report")).isCloseTo(Duration.ofHours(72).toSeconds(), within(TOLERANCE_S));
    }

    @Test
    @DisplayName("reclassification: withdrawing MAJOR drops the incident from the gauge, re-escalating restarts the 4 h clock")
    void reclassificationRemovesAndRestoresTheCountdown() {
        IctIncident incident = reportMajor(Instant.now().minus(Duration.ofHours(2)));
        assertThat(due("initial_report")).isNotNaN();

        service.classify(incident.getId(), Severity.MEDIUM, "Impact reassessed below the major threshold",
                Map.of(), UUID.randomUUID());
        assertThat(incident.getDowngradedAt()).isNotNull();
        for (String type : List.of("classification", "initial_report", "intermediate_report", "final_report")) {
            assertThat(due(type)).as("downgraded: " + type).isNaN();
        }
        assertThat(breaches("initial_report")).isZero();

        service.classify(incident.getId(), Severity.MAJOR, "New facts: major again", Map.of(), UUID.randomUUID());
        assertThat(incident.getDowngradedAt()).isNull();
        assertThat(due("classification")).as("4 h clock restarts at re-classification")
                .isCloseTo(Duration.ofHours(4).toSeconds(), within(TOLERANCE_S));
        assertThat(due("initial_report")).isCloseTo(Duration.ofHours(22).toSeconds(), within(TOLERANCE_S));
    }

    @Test
    @DisplayName("the meter is exposed under the Prometheus name the alert rules read")
    void meterNameIsWhatTheAlertsRead() {
        assertThat(registry.find(DUE).gauges()).hasSize(4);
        assertThat(registry.find(BREACHES).gauges()).hasSize(5);
        assertThat(Optional.ofNullable(registry.find(DUE).tag("breach_type", "final_report").gauge())).isPresent();
    }
}
