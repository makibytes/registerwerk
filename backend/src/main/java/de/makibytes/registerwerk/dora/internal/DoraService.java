package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncident.Category;
import de.makibytes.registerwerk.dora.api.IctIncident.Severity;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DORA compliance service — ICT incident lifecycle and third-party provider register.
 *
 * Reporting timelines per DORA Art. 19(4) and RTS (EU) 2025/301 (major incidents):
 *   initial notification — within 4h of classification, no later than 24h from awareness
 *   intermediate report  — within 72h of the initial notification
 *   final report         — within 1 month of the intermediate report
 *
 * <p>This service tracks all three deadlines: the 4h-from-classification and 24h-from-
 * awareness sub-deadlines (both gate the same initial notification — whichever falls due
 * first is binding) and the final report milestone.
 * The final deadline is set conservatively to one month from detection — earlier than
 * the theoretical latest (24h + 72h + 1 month chained off submissions), so a met
 * deadline here is always compliant.
 */
@Service
public class DoraService {

    private static final Logger log = LoggerFactory.getLogger(DoraService.class);

    private final IctIncidentRepository incidentRepository;
    private final ThirdPartyProviderRepository providerRepository;
    private final ResilienceTestRepository resilienceTestRepository;
    private final ApplicationEventPublisher events;

    public DoraService(IctIncidentRepository incidentRepository,
                ThirdPartyProviderRepository providerRepository,
                ResilienceTestRepository resilienceTestRepository,
                ApplicationEventPublisher events,
                MeterRegistry meterRegistry) {
        this.incidentRepository = incidentRepository;
        this.providerRepository = providerRepository;
        this.resilienceTestRepository = resilienceTestRepository;
        this.events = events;

        // Live-queried at scrape time (all 4 are cheap indexed lookups the checkDeadlines() job
        // already runs daily) rather than only updated once a day.
        registerBreachGauge(meterRegistry, "classification", () -> incidentRepository.findOverdueClassificationReports(Instant.now()).size());
        registerBreachGauge(meterRegistry, "initial_report", () -> incidentRepository.findOverdueInitialReports(Instant.now()).size());
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

    @Transactional
    public IctIncident reportIncident(String title, String description,
                                      Category category, Severity severity,
                                      String sourceEventType, UUID sourceEventRef,
                                      UUID createdBy) {
        IctIncident incident = new IctIncident();
        incident.setTitle(title);
        incident.setDescription(description);
        incident.setCategory(category);
        incident.setSeverity(severity);
        incident.setSourceEventType(sourceEventType);
        incident.setSourceEventRef(sourceEventRef);
        incident.setCreatedBy(createdBy);
        incident.setDetectedAt(Instant.now());

        // DORA Art. 19 / RTS (EU) 2025/301 — compute deadlines for major incidents.
        // 72h is the INTERMEDIATE report deadline, not the final one; the final
        // root-cause report is due one month later. Conservative: from detection.
        //
        // Classification happens at report time in this model (there is no separate
        // reclassification workflow yet) — so classifiedAt == detectedAt here, and the 4h
        // deadline is the STRICTER of the two initial-notification sub-deadlines: it falls
        // due 20 hours before the 24h-from-detection deadline below.
        if (severity == Severity.MAJOR) {
            incident.setClassifiedAt(incident.getDetectedAt());
            incident.setClassificationDeadline(incident.getDetectedAt().plus(4, ChronoUnit.HOURS));
            incident.setInitialReportDeadline(incident.getDetectedAt().plus(24, ChronoUnit.HOURS));
            incident.setFinalReportDeadline(incident.getDetectedAt().plus(30, ChronoUnit.DAYS));
        }

        IctIncident saved = incidentRepository.save(incident);
        log.warn("ICT incident created: id={} severity={} title={}", saved.getId(), severity, title);
        return saved;
    }

    @Transactional
    public IctIncident updateStatus(UUID incidentId, IctIncident.Status newStatus,
                                    String rootCause, String remediationSteps, UUID actorId) {
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        IctIncident.Status previousStatus = incident.getStatus();
        incident.setStatus(newStatus);
        if (rootCause != null) incident.setRootCause(rootCause);
        if (remediationSteps != null) incident.setRemediationSteps(remediationSteps);
        if (newStatus == IctIncident.Status.CONTAINED) incident.setContainedAt(Instant.now());
        if (newStatus == IctIncident.Status.RESOLVED) incident.setResolvedAt(Instant.now());
        if (newStatus == IctIncident.Status.REPORTED_TO_AUTHORITY && incident.getInitialReportedAt() == null) {
            incident.setInitialReportedAt(Instant.now());
        }
        IctIncident saved = incidentRepository.save(incident);
        events.publishEvent(new IctIncidentStatusChangedEvent(saved.getId(), actorId, "REGISTRY_ADMIN", Map.of(
                "previousStatus", previousStatus.name(),
                "newStatus", newStatus.name()
        )));
        return saved;
    }

    @Transactional
    public IctIncident markReportedToAuthority(UUID incidentId, String authorityRef,
                                                boolean isFinalReport, UUID actorId) {
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        incident.setAuthorityRef(authorityRef);
        incident.setReportedBy(actorId);
        if (!isFinalReport) {
            incident.setInitialReportedAt(Instant.now());
        } else {
            incident.setFinalReportedAt(Instant.now());
            incident.setStatus(IctIncident.Status.REPORTED_TO_AUTHORITY);
        }
        IctIncident saved = incidentRepository.save(incident);
        events.publishEvent(new IctIncidentReportedEvent(saved.getId(), actorId, "REGISTRY_ADMIN", Map.of(
                "authorityRef", authorityRef != null ? authorityRef : "",
                "isFinalReport", isFinalReport
        )));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<IctIncident> listOpen() {
        return incidentRepository.findByStatusNotInOrderByDetectedAtDesc(
                List.of(IctIncident.Status.CLOSED, IctIncident.Status.REPORTED_TO_AUTHORITY));
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

    /** Daily check for overdue DORA reporting deadlines. */
    @SchedulerLock(name = "doraOverdueCheck", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 7 * * *")
    @Transactional(readOnly = true)
    public void checkDeadlines() {
        Instant now = Instant.now();
        List<IctIncident> overdueClassification = incidentRepository.findOverdueClassificationReports(now);
        if (!overdueClassification.isEmpty()) {
            log.error("DORA DEADLINE BREACH: {} incident(s) have missed the 4h classification-to-report deadline: {}",
                    overdueClassification.size(),
                    overdueClassification.stream().map(i -> i.getId().toString()).toList());
        }
        List<IctIncident> overdueInitial = incidentRepository.findOverdueInitialReports(now);
        if (!overdueInitial.isEmpty()) {
            log.error("DORA DEADLINE BREACH: {} incident(s) have missed the 24h initial report deadline: {}",
                    overdueInitial.size(),
                    overdueInitial.stream().map(i -> i.getId().toString()).toList());
        }
        List<IctIncident> overdueFinal = incidentRepository.findOverdueFinalReports(now);
        if (!overdueFinal.isEmpty()) {
            log.error("DORA DEADLINE BREACH: {} incident(s) have missed the final report deadline (1 month): {}",
                    overdueFinal.size(),
                    overdueFinal.stream().map(i -> i.getId().toString()).toList());
        }
        List<ResilienceTest> overdueTests = resilienceTestRepository
                .findByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate.now());
        if (!overdueTests.isEmpty()) {
            log.error("DORA DEADLINE BREACH: {} resilience test(s) are overdue for re-testing (Art. 24/25): {}",
                    overdueTests.size(),
                    overdueTests.stream().map(t -> t.getId().toString()).toList());
        }
    }
}
