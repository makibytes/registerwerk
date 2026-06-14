package de.makibytes.registerwerk.dora.internal;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncident.Category;
import de.makibytes.registerwerk.dora.api.IctIncident.Severity;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * DORA compliance service — ICT incident lifecycle and third-party provider register.
 *
 * Reporting timelines per DORA Art. 19(4) and RTS (EU) 2025/301 (major incidents):
 *   initial notification — within 4h of classification, no later than 24h from awareness
 *   intermediate report  — within 72h of the initial notification
 *   final report         — within 1 month of the intermediate report
 *
 * <p>This service tracks the two report milestones exposed by the API (initial/final).
 * The final deadline is set conservatively to one month from detection — earlier than
 * the theoretical latest (24h + 72h + 1 month chained off submissions), so a met
 * deadline here is always compliant.
 */
@Service
public class DoraService {

    private static final Logger log = LoggerFactory.getLogger(DoraService.class);

    private final IctIncidentRepository incidentRepository;
    private final ThirdPartyProviderRepository providerRepository;
    private final ApplicationEventPublisher events;

    DoraService(IctIncidentRepository incidentRepository,
                ThirdPartyProviderRepository providerRepository,
                ApplicationEventPublisher events) {
        this.incidentRepository = incidentRepository;
        this.providerRepository = providerRepository;
        this.events = events;
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
        if (severity == Severity.MAJOR) {
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
        incident.setStatus(newStatus);
        if (rootCause != null) incident.setRootCause(rootCause);
        if (remediationSteps != null) incident.setRemediationSteps(remediationSteps);
        if (newStatus == IctIncident.Status.CONTAINED) incident.setContainedAt(Instant.now());
        if (newStatus == IctIncident.Status.RESOLVED) incident.setResolvedAt(Instant.now());
        if (newStatus == IctIncident.Status.REPORTED_TO_AUTHORITY && incident.getInitialReportedAt() == null) {
            incident.setInitialReportedAt(Instant.now());
        }
        return incidentRepository.save(incident);
    }

    @Transactional
    public IctIncident markReportedToAuthority(UUID incidentId, String authorityRef,
                                                boolean isFinalReport) {
        IctIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("IctIncident", incidentId));
        incident.setAuthorityRef(authorityRef);
        if (!isFinalReport) {
            incident.setInitialReportedAt(Instant.now());
        } else {
            incident.setFinalReportedAt(Instant.now());
            incident.setStatus(IctIncident.Status.REPORTED_TO_AUTHORITY);
        }
        return incidentRepository.save(incident);
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

    /** Daily check for overdue DORA reporting deadlines. */
    @Scheduled(cron = "0 0 7 * * *")
    @Transactional(readOnly = true)
    public void checkDeadlines() {
        Instant now = Instant.now();
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
    }
}
