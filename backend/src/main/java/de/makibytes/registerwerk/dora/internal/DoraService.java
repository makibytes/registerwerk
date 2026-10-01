package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncident.Category;
import de.makibytes.registerwerk.dora.api.IctIncident.Severity;
import de.makibytes.registerwerk.dora.api.IctIncident.Status;
import de.makibytes.registerwerk.dora.api.IctIncidentAlert;
import de.makibytes.registerwerk.dora.api.IctIncidentAlertRepository;
import de.makibytes.registerwerk.dora.api.IctIncidentReport;
import de.makibytes.registerwerk.dora.api.IctIncidentReportRepository;
import de.makibytes.registerwerk.dora.events.IctIncidentClassifiedEvent;
import de.makibytes.registerwerk.dora.events.IctIncidentCreatedEvent;
import de.makibytes.registerwerk.dora.events.IctIncidentDeadlineBreachedEvent;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.dora.events.IctIncidentReportedEvent;
import de.makibytes.registerwerk.dora.events.IctIncidentStatusChangedEvent;
import de.makibytes.registerwerk.dora.events.ResilienceTestUpdatedEvent;
import de.makibytes.registerwerk.dora.events.ThirdPartyProviderChangedEvent;
import java.math.BigDecimal;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DORA compliance service — ICT incident lifecycle and third-party provider register.
 *
 * Reporting timelines per DORA Art. 19(4) and RTS (EU) 2025/301 (major incidents):
 *   initial notification — within 4h of classification, no later than 24h from awareness
 *   intermediate report  — within 72h of the initial notification
 *   final report         — within 1 month of the intermediate report
 *
 * <p>All clocks run from real anchors (operator-entered awareness time, classification time, the
 * recorded initial submission) and are recomputed on reclassification. The final deadline is set
 * conservatively to 30 days from awareness (earlier than "one month after the latest intermediate
 * report"), so meeting it here is always compliant. Whether and when an incident is MAJOR is a
 * documented human decision (classify endpoint with reason and criteria), see parked decision T6-16.
 */
@Service
public class DoraService {

    private static final Logger log = LoggerFactory.getLogger(DoraService.class);

    private final IctIncidentRepository incidentRepository;
    private final ThirdPartyProviderRepository providerRepository;
    private final ResilienceTestRepository resilienceTestRepository;
    private final ApplicationEventPublisher events;
    private final IctIncidentReportRepository reportRepository;
    private final IctIncidentAlertRepository alertRepository;
    private final DualControlGate dualControlGate;

    /** Tolerated clock skew for operator-entered timestamps. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);
    /** A still-unresolved breach is alerted again after this long. */
    private static final Duration REALERT_INTERVAL = Duration.ofHours(6);

    public DoraService(IctIncidentRepository incidentRepository,
                ThirdPartyProviderRepository providerRepository,
                ResilienceTestRepository resilienceTestRepository,
                ApplicationEventPublisher events,
                MeterRegistry meterRegistry,
                IctIncidentReportRepository reportRepository,
                IctIncidentAlertRepository alertRepository,
                DualControlGate dualControlGate) {
        this.incidentRepository = incidentRepository;
        this.providerRepository = providerRepository;
        this.resilienceTestRepository = resilienceTestRepository;
        this.events = events;
        this.reportRepository = reportRepository;
        this.alertRepository = alertRepository;
        this.dualControlGate = dualControlGate;

        // Live-queried at scrape time (all 4 are cheap indexed lookups the checkDeadlines() job
        // already runs daily) rather than only updated once a day.
        registerBreachGauge(meterRegistry, "classification", () -> incidentRepository.findOverdueClassificationReports(Instant.now()).size());
        registerBreachGauge(meterRegistry, "initial_report", () -> incidentRepository.findOverdueInitialReports(Instant.now()).size());
        registerBreachGauge(meterRegistry, "intermediate_report", () -> incidentRepository.findOverdueIntermediateReports(Instant.now()).size());
        registerBreachGauge(meterRegistry, "final_report", () -> incidentRepository.findOverdueFinalReports(Instant.now()).size());
        registerBreachGauge(meterRegistry, "resilience_test", () -> resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate.now()).size());
    }

    private static void registerBreachGauge(MeterRegistry meterRegistry, String breachType,
                                             java.util.function.Supplier<Integer> counter) {
        Gauge.builder("registerwerk_dora_deadline_breaches", counter, c -> (double) c.get())
                .tag("breach_type", breachType)
                .description("Count of overdue DORA reporting deadlines/resilience tests, tagged by breach_type")
                .register(meterRegistry);
    }

    /**
     * Enters an incident. {@code awarenessAt} (null = now, never in the future) anchors the 24 h and
     * 1 month clocks; a MAJOR incident is classified at entry and needs a classification reason.
     */
    @Transactional
    public IctIncident reportIncident(String title, String description,
                                      Category category, Severity severity,
                                      Instant awarenessAt, String classificationReason,
                                      Map<String, Object> classificationCriteria,
                                      String sourceEventType, UUID sourceEventRef,
                                      UUID createdBy) {
        Instant now = Instant.now();
        if (severity == Severity.MAJOR && (classificationReason == null || classificationReason.isBlank())) {
            throw new IllegalArgumentException("A MAJOR classification needs a classification reason");
        }
        IctIncident incident = new IctIncident();
        incident.setTitle(title);
        incident.setDescription(description);
        incident.setCategory(category);
        incident.setSeverity(severity);
        incident.setSourceEventType(sourceEventType);
        incident.setSourceEventRef(sourceEventRef);
        incident.setCreatedBy(createdBy);
        incident.setDetectedAt(now);
        incident.setAwarenessAt(validatedAwareness(awarenessAt, now));
        if (classificationReason != null && !classificationReason.isBlank()) {
            incident.setClassificationReason(classificationReason);
            incident.setClassificationCriteria(classificationCriteria);
            incident.setClassifiedBy(createdBy);
        }
        if (severity == Severity.MAJOR) {
            incident.setClassifiedAt(now);
        }
        computeDeadlines(incident);
        return persistNew(incident, createdBy);
    }

    /**
     * Opens an unclassified draft for an automatic trigger (T6-16 interim: a person classifies it later).
     * One open draft per source event type: a burst of the same failure does not flood the register.
     * Never throws into the publishing transaction's caller beyond the usual persistence errors.
     */
    @Transactional
    public Optional<IctIncident> openDraftIncident(String title, String description, Category category,
                                                   String sourceEventType, UUID sourceEventRef) {
        incidentRepository.lockDraftCreation("dora-draft:" + sourceEventType);
        Optional<IctIncident> existing = incidentRepository
                .findFirstBySourceEventTypeAndClassificationPendingTrueAndStatusNot(sourceEventType, Status.CLOSED);
        if (existing.isPresent()) {
            log.warn("DORA draft incident for {} already open (id={}), not opening another",
                    sourceEventType, existing.get().getId());
            return Optional.empty();
        }
        Instant now = Instant.now();
        IctIncident incident = new IctIncident();
        incident.setTitle(title);
        incident.setDescription(description);
        incident.setCategory(category);
        incident.setSeverity(Severity.HIGH);
        incident.setSourceEventType(sourceEventType);
        incident.setSourceEventRef(sourceEventRef);
        incident.setDetectedAt(now);
        incident.setAwarenessAt(now);
        incident.setClassificationPending(true);
        computeDeadlines(incident);
        return Optional.of(persistNew(incident, null));
    }

    private IctIncident persistNew(IctIncident incident, UUID actorId) {
        IctIncident saved = incidentRepository.save(incident);
        log.warn("ICT incident created: id={} severity={} title={}", saved.getId(), saved.getSeverity(), saved.getTitle());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("severity", saved.getSeverity().name());
        details.put("category", saved.getCategory().name());
        details.put("awarenessAt", saved.getAwarenessAt().toString());
        details.put("classificationPending", saved.isClassificationPending());
        if (saved.getSourceEventType() != null) details.put("sourceEventType", saved.getSourceEventType());
        if (saved.getClassificationReason() != null) details.put("classificationReason", saved.getClassificationReason());
        events.publishEvent(new IctIncidentCreatedEvent(saved.getId(), actorId,
                actorId != null ? "REGISTRY_ADMIN" : "SYSTEM", details));
        return saved;
    }

    private static Instant validatedAwareness(Instant awarenessAt, Instant now) {
        if (awarenessAt == null) return now;
        if (awarenessAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("awarenessAt must not be in the future");
        }
        return awarenessAt.isAfter(now) ? now : awarenessAt;
    }

    /**
     * Recomputes every deadline from its real anchor: 24 h and 1 month from awareness (for every
     * incident), 4 h from classification (MAJOR), 72 h from the initial notification (once it exists).
     */
    static void computeDeadlines(IctIncident incident) {
        incident.setInitialReportDeadline(incident.getAwarenessAt().plus(24, ChronoUnit.HOURS));
        incident.setFinalReportDeadline(incident.getAwarenessAt().plus(30, ChronoUnit.DAYS));
        if (incident.getClassifiedAt() != null && incident.getSeverity() == Severity.MAJOR) {
            incident.setClassificationDeadline(incident.getClassifiedAt().plus(4, ChronoUnit.HOURS));
        }
        if (incident.getInitialReportedAt() != null) {
            incident.setIntermediateReportDeadline(incident.getInitialReportedAt().plus(72, ChronoUnit.HOURS));
        }
    }

    /**
     * (Re)classifies an incident. Escalating to MAJOR starts the 4 h clock now and recomputes the
     * others from awareness; withdrawing MAJOR needs a second approver and leaves a marker (the incident
     * stays MAJOR-history, it is not silently dropped from monitoring by a status flip).
     */
    @Transactional
    public IctIncident classify(UUID incidentId, Severity newSeverity, String reason,
                                Map<String, Object> criteria, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A classification reason is required");
        }
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        Instant now = Instant.now();
        Severity previous = incident.getSeverity();
        boolean wasActiveMajor = previous == Severity.MAJOR && incident.getDowngradedAt() == null;
        UUID approverId = null;
        if (wasActiveMajor && newSeverity != Severity.MAJOR) {
            approverId = dualControlGate.require("DORA_INCIDENT_DOWNGRADE");
            incident.setDowngradedAt(now);
            incident.setDowngradeReason(reason);
        } else if (newSeverity == Severity.MAJOR && !wasActiveMajor) {
            incident.setClassifiedAt(now);
            incident.setDowngradedAt(null);
            incident.setDowngradeReason(null);
        }
        incident.setSeverity(newSeverity);
        incident.setClassificationReason(reason);
        incident.setClassificationCriteria(criteria);
        incident.setClassifiedBy(actorId);
        incident.setClassificationPending(false);
        computeDeadlines(incident);
        IctIncident saved = incidentRepository.save(incident);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousSeverity", previous.name());
        details.put("newSeverity", newSeverity.name());
        details.put("reason", reason);
        if (criteria != null) details.put("criteria", criteria);
        details.put("classifiedAt", saved.getClassifiedAt() != null ? saved.getClassifiedAt().toString() : "");
        details.put("classificationDeadline", saved.getClassificationDeadline() != null ? saved.getClassificationDeadline().toString() : "");
        details.put("initialReportDeadline", saved.getInitialReportDeadline().toString());
        details.put("finalReportDeadline", saved.getFinalReportDeadline().toString());
        details.put("downgraded", saved.getDowngradedAt() != null);
        events.publishEvent(new IctIncidentClassifiedEvent(saved.getId(), actorId, "REGISTRY_ADMIN", approverId, details));
        return saved;
    }

    /** Allowed manual status moves. REPORTED_TO_AUTHORITY is reached only by recording the final report. */
    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.DETECTED, EnumSet.of(Status.INVESTIGATING, Status.CONTAINED, Status.RESOLVED, Status.CLOSED),
            Status.INVESTIGATING, EnumSet.of(Status.CONTAINED, Status.RESOLVED, Status.CLOSED),
            Status.CONTAINED, EnumSet.of(Status.INVESTIGATING, Status.RESOLVED, Status.CLOSED),
            Status.RESOLVED, EnumSet.of(Status.INVESTIGATING, Status.CLOSED),
            Status.REPORTED_TO_AUTHORITY, EnumSet.of(Status.INVESTIGATING, Status.CLOSED),
            Status.CLOSED, EnumSet.noneOf(Status.class));

    /** What still prevents CLOSED; empty when the incident can be closed. */
    public List<String> closeBlockers(IctIncident incident) {
        List<String> blockers = new ArrayList<>();
        if (incident.isClassificationPending()) {
            blockers.add("CLASSIFICATION_PENDING");
        }
        if (incident.getSeverity() == Severity.MAJOR && incident.getDowngradedAt() == null) {
            if (incident.getInitialReportedAt() == null) blockers.add("INITIAL_REPORT_MISSING");
            if (incident.getFinalReportedAt() == null) blockers.add("FINAL_REPORT_MISSING");
        }
        return blockers;
    }

    @Transactional
    public IctIncident updateStatus(UUID incidentId, IctIncident.Status newStatus,
                                    String rootCause, String remediationSteps, UUID actorId) {
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        IctIncident.Status previousStatus = incident.getStatus();
        if (newStatus == Status.REPORTED_TO_AUTHORITY) {
            throw new InvalidStateTransitionException(
                    "REPORTED_TO_AUTHORITY is set only by recording the final authority report");
        }
        if (newStatus != previousStatus && !TRANSITIONS.get(previousStatus).contains(newStatus)) {
            throw new InvalidStateTransitionException("IctIncident", previousStatus.name(), newStatus.name());
        }
        if (previousStatus == Status.CLOSED) {
            throw new InvalidStateTransitionException("IctIncident is CLOSED and cannot be modified");
        }
        if (newStatus == Status.CLOSED) {
            List<String> blockers = closeBlockers(incident);
            if (!blockers.isEmpty()) {
                throw new InvalidStateTransitionException("IctIncident cannot be closed: " + String.join(", ", blockers));
            }
            if (incident.getSeverity() == Severity.MAJOR && incident.getDowngradedAt() == null) {
                dualControlGate.require("DORA_INCIDENT_CLOSE");
            }
        }
        incident.setStatus(newStatus);
        if (rootCause != null) incident.setRootCause(rootCause);
        if (remediationSteps != null) incident.setRemediationSteps(remediationSteps);
        if (newStatus == IctIncident.Status.CONTAINED && incident.getContainedAt() == null) incident.setContainedAt(Instant.now());
        if (newStatus == IctIncident.Status.RESOLVED && incident.getResolvedAt() == null) incident.setResolvedAt(Instant.now());
        IctIncident saved = incidentRepository.save(incident);
        events.publishEvent(new IctIncidentStatusChangedEvent(saved.getId(), actorId, "REGISTRY_ADMIN", Map.of(
                "previousStatus", previousStatus.name(),
                "newStatus", newStatus.name()
        )));
        return saved;
    }

    /**
     * Records an authority submission as an append-only report row. The incident's current-state
     * timestamps and {@code authorityRef} are written once (first submission of a type); further
     * submissions of the same type are additional rows (corrections), never overwrites.
     */
    @Transactional
    public IctIncident markReportedToAuthority(UUID incidentId, IctIncidentReport.Type type, String authorityRef,
                                               String note, Instant submittedAt, UUID actorId) {
        if (authorityRef == null || authorityRef.isBlank()) {
            throw new IllegalArgumentException("An authority reference is required as evidence of the submission");
        }
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        if (incident.getSeverity() != Severity.MAJOR || incident.getDowngradedAt() != null) {
            throw new InvalidStateTransitionException("Authority reports apply to MAJOR incidents only; classify the incident first");
        }
        if (type != IctIncidentReport.Type.INITIAL && incident.getInitialReportedAt() == null) {
            throw new InvalidStateTransitionException(type + " report requires an initial notification first");
        }
        Instant now = Instant.now();
        Instant at = submittedAt != null ? submittedAt : now;
        if (at.isAfter(now.plus(CLOCK_SKEW))) throw new IllegalArgumentException("submittedAt must not be in the future");
        if (at.isBefore(incident.getAwarenessAt())) throw new IllegalArgumentException("submittedAt precedes the awareness time");

        reportRepository.save(new IctIncidentReport(incident.getId(), type, at, authorityRef, actorId, note));
        boolean first = false;
        switch (type) {
            case INITIAL -> {
                if (incident.getInitialReportedAt() == null) {
                    first = true;
                    incident.setInitialReportedAt(at);
                    incident.setAuthorityRef(authorityRef);
                    incident.setIntermediateReportDeadline(at.plus(72, ChronoUnit.HOURS));
                }
            }
            case INTERMEDIATE -> {
                if (incident.getIntermediateReportedAt() == null) {
                    first = true;
                    incident.setIntermediateReportedAt(at);
                }
            }
            case FINAL -> {
                if (incident.getFinalReportedAt() == null) {
                    first = true;
                    incident.setFinalReportedAt(at);
                    if (incident.getStatus() != Status.CLOSED) incident.setStatus(Status.REPORTED_TO_AUTHORITY);
                }
            }
        }
        incident.setReportedBy(actorId);
        IctIncident saved = incidentRepository.save(incident);
        events.publishEvent(new IctIncidentReportedEvent(saved.getId(), actorId, "REGISTRY_ADMIN", Map.of(
                "authorityRef", authorityRef,
                "reportType", type.name(),
                "isFinalReport", type == IctIncidentReport.Type.FINAL,
                "firstOfType", first,
                "submittedAt", at.toString()
        )));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<IctIncident> listOpen() {
        return incidentRepository.findOpenOrReportsOutstanding();
    }

    @Transactional(readOnly = true)
    public List<IctIncidentReport> listReports(UUID incidentId) {
        return reportRepository.findByIncidentIdOrderBySubmittedAtAscRecordedAtAsc(incidentId);
    }

    @Transactional(readOnly = true)
    public IctIncident getIncident(UUID id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", id));
    }

    @Transactional(readOnly = true)
    public List<ThirdPartyProvider> listProviders() {
        return providerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ThirdPartyProvider> listExpiringContracts() {
        return providerRepository.findByContractEndBeforeOrderByContractEndAsc(
                LocalDate.now().plusDays(90));
    }

    /**
     * Registers a new critical/important ICT third-party provider in the DORA Register of
     * Information (Art. 28). Previously the only way a {@link ThirdPartyProvider} row was ever
     * created was {@code bootstrap.DemoDataSeeder} — a bank onboarding this system had no way
     * to enter its own providers.
     */
    @Transactional
    public ThirdPartyProvider createProvider(
            String name, String category, ThirdPartyProvider.Criticality criticality,
            String lei, String country, LocalDate contractStart, LocalDate contractEnd,
            boolean subOutsourcing, String subOutsourcingDetails, String primaryContact,
            BigDecimal slaAvailabilityPct, Integer rtoHours, Integer rpoHours, String notes,
            UUID actorId) {
        ThirdPartyProvider provider = new ThirdPartyProvider();
        provider.setName(name);
        provider.setCategory(category);
        provider.setCriticality(criticality != null ? criticality : ThirdPartyProvider.Criticality.STANDARD);
        provider.setLei(lei);
        provider.setCountry(country);
        provider.setContractStart(contractStart);
        provider.setContractEnd(contractEnd);
        provider.setSubOutsourcing(subOutsourcing);
        provider.setSubOutsourcingDetails(subOutsourcingDetails);
        provider.setPrimaryContact(primaryContact);
        provider.setSlaAvailabilityPct(slaAvailabilityPct);
        provider.setRtoHours(rtoHours);
        provider.setRpoHours(rpoHours);
        provider.setNotes(notes);

        ThirdPartyProvider saved = providerRepository.save(provider);
        log.info("DORA third-party provider registered: id={} name={} criticality={}",
                saved.getId(), name, provider.getCriticality());
        events.publishEvent(new ThirdPartyProviderChangedEvent(saved.getId(), "REGISTERED", actorId, "REGISTRY_ADMIN",
                Map.of("name", name, "criticality", provider.getCriticality().name())));
        return saved;
    }

    /**
     * Updates an existing ICT third-party provider — all fields optional/overwriting, matching
     * the {@code CustomerController.updateEntity} full-replace convention used elsewhere. Also
     * the only write path for {@code notifiedAuthority}/{@code notifiedAt} (Art. 28(3) — the
     * competent authority must be notified before entering into an arrangement with a critical
     * provider).
     */
    @Transactional
    public ThirdPartyProvider updateProvider(
            UUID providerId, String name, String category, ThirdPartyProvider.Criticality criticality,
            String lei, String country, LocalDate contractStart, LocalDate contractEnd,
            boolean subOutsourcing, String subOutsourcingDetails, String primaryContact,
            BigDecimal slaAvailabilityPct, Integer rtoHours, Integer rpoHours,
            boolean notifiedAuthority, String notes, UUID actorId) {
        ThirdPartyProvider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new EntityNotFoundException("ThirdPartyProvider", providerId));

        provider.setName(name);
        provider.setCategory(category);
        provider.setCriticality(criticality != null ? criticality : provider.getCriticality());
        provider.setLei(lei);
        provider.setCountry(country);
        provider.setContractStart(contractStart);
        provider.setContractEnd(contractEnd);
        provider.setSubOutsourcing(subOutsourcing);
        provider.setSubOutsourcingDetails(subOutsourcingDetails);
        provider.setPrimaryContact(primaryContact);
        provider.setSlaAvailabilityPct(slaAvailabilityPct);
        provider.setRtoHours(rtoHours);
        provider.setRpoHours(rpoHours);
        provider.setNotes(notes);
        boolean authorityNotificationChanged = notifiedAuthority && !provider.isNotifiedAuthority();
        provider.setNotifiedAuthority(notifiedAuthority);
        if (authorityNotificationChanged) {
            provider.setNotifiedAt(Instant.now());
        }

        ThirdPartyProvider saved = providerRepository.save(provider);
        log.info("DORA third-party provider updated: id={} name={}", saved.getId(), name);
        events.publishEvent(new ThirdPartyProviderChangedEvent(saved.getId(), "UPDATED", actorId, "REGISTRY_ADMIN",
                Map.of("name", name, "notifiedAuthority", notifiedAuthority)));
        return saved;
    }

    // ── Resilience Testing (Art. 24/25) ───────────────────────────────────────

    @Transactional
    public ResilienceTest recordResilienceTest(ResilienceTest.TestType testType, String scope,
                                                boolean tlptRequired, UUID thirdPartyProviderId,
                                                LocalDate performedAt, LocalDate nextDueDate,
                                                ResilienceTest.Result result, String findings,
                                                String testerName, String reportRef, UUID createdBy) {
        ResilienceTest test = new ResilienceTest();
        test.setTestType(testType);
        test.setScope(scope);
        test.setTlptRequired(tlptRequired);
        test.setThirdPartyProviderId(thirdPartyProviderId);
        test.setPerformedAt(performedAt);
        test.setNextDueDate(nextDueDate);
        test.setResult(result);
        test.setFindings(findings);
        test.setTesterName(testerName);
        test.setReportRef(reportRef);
        test.setCreatedBy(createdBy);
        ResilienceTest saved = resilienceTestRepository.save(test);
        log.info("DORA resilience test recorded: id={} type={} scope={} result={}",
                saved.getId(), testType, scope, result);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ResilienceTest> listResilienceTests() {
        return resilienceTestRepository.findAllByOrderByPerformedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ResilienceTest> listOverdueResilienceTests() {
        return resilienceTestRepository.findByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate.now());
    }

    /**
     * Updates a resilience test's result/findings — previously {@code recordResilienceTest}
     * was the only write path, so a test recorded as {@code FINDINGS_OPEN} could never be
     * closed out to {@code PASSED} once remediation was done.
     */
    @Transactional
    public ResilienceTest updateResilienceTestResult(
            UUID testId, ResilienceTest.Result result, String findings, String reportRef,
            UUID actorId) {
        ResilienceTest test = resilienceTestRepository.findById(testId)
                .orElseThrow(() -> new EntityNotFoundException("ResilienceTest", testId));
        test.setResult(result);
        if (findings != null) test.setFindings(findings);
        if (reportRef != null) test.setReportRef(reportRef);

        ResilienceTest saved = resilienceTestRepository.save(test);
        log.info("DORA resilience test updated: id={} result={}", saved.getId(), result);
        events.publishEvent(new ResilienceTestUpdatedEvent(saved.getId(), actorId, "REGISTRY_ADMIN",
                Map.of("result", result.name())));
        return saved;
    }

    /**
     * Overdue check every 15 minutes (the initial notification runs on a 4 h clock). Monitoring runs
     * until the final report, independent of status. Each (incident, breach type) is alerted once and
     * again every few hours while it stays overdue.
     */
    @SchedulerLock(name = "doraOverdueCheck", lockAtMostFor = "PT10M")
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void checkDeadlines() {
        Instant now = Instant.now();
        breach("classification", "the 4h initial-notification deadline from classification",
                incidentRepository.findOverdueClassificationReports(now), IctIncident::getClassificationDeadline, now);
        breach("initial_report", "the 24h-from-awareness initial notification deadline",
                incidentRepository.findOverdueInitialReports(now), IctIncident::getInitialReportDeadline, now);
        breach("intermediate_report", "the 72h intermediate report deadline",
                incidentRepository.findOverdueIntermediateReports(now), IctIncident::getIntermediateReportDeadline, now);
        breach("final_report", "the final report deadline (1 month)",
                incidentRepository.findOverdueFinalReports(now), IctIncident::getFinalReportDeadline, now);
        List<ResilienceTest> overdueTests = resilienceTestRepository
                .findByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate.now());
        if (!overdueTests.isEmpty()) {
            log.error("DORA DEADLINE BREACH: {} resilience test(s) are overdue for re-testing (Art. 24/25): {}",
                    overdueTests.size(),
                    overdueTests.stream().map(t -> t.getId().toString()).toList());
        }
    }

    private void breach(String breachType, String what, List<IctIncident> overdue,
                        java.util.function.Function<IctIncident, Instant> deadline, Instant now) {
        if (overdue.isEmpty()) return;
        log.error("DORA DEADLINE BREACH: {} incident(s) have missed {}: {}", overdue.size(), what,
                overdue.stream().map(i -> i.getId().toString()).toList());
        for (IctIncident incident : overdue) {
            IctIncidentAlert alert = alertRepository.findByIncidentIdAndBreachType(incident.getId(), breachType).orElse(null);
            if (alert != null && alert.getAlertedAt().isAfter(now.minus(REALERT_INTERVAL))) continue;
            if (alert == null) {
                alert = new IctIncidentAlert(incident.getId(), breachType);
            }
            alert.setAlertedAt(now);
            alertRepository.save(alert);
            events.publishEvent(new IctIncidentDeadlineBreachedEvent(
                    incident.getId(), incident.getTitle(), breachType, deadline.apply(incident)));
        }
    }
}
