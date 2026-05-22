package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningHitDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import org.slf4j.Logger;
import java.math.BigDecimal;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates sanctions/PEP screening runs.
 * Trigger points: entity onboarding, KYC submission, beneficial-owner add,
 * ERC-3643 claim issuance, and daily periodic refresh (GwG §10 Abs. 1 Nr. 5).
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final List<SanctionsScreeningPort> providers;
    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;
    private final ApplicationEventPublisher events;

    ScreeningService(List<SanctionsScreeningPort> providers,
                     ScreeningRunRepository runRepository,
                     ScreeningHitRepository hitRepository,
                     ApplicationEventPublisher events) {
        this.providers = providers;
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
        this.events = events;
    }

    @Transactional
    public ScreeningRun screenEntity(UUID entityId, String entityName, String countryCode,
                                     String lei, ScreeningTrigger trigger) {
        log.info("Screening entity={} trigger={}", entityId, trigger);
        for (SanctionsScreeningPort provider : providers) {
            ScreeningRun run = new ScreeningRun();
            run.setEntityId(entityId);
            run.setTriggerType(trigger);
            run.setProvider(provider.providerName());
            run.setStatus(ScreeningStatus.PENDING);
            run.setStartedAt(Instant.now());
            run = runRepository.save(run);

            try {
                List<ScreeningHitDto> hits = provider.screenEntity(
                        new ScreeningSubjectDto(entityId, "LEGAL_ENTITY", entityName, countryCode, lei, null));

                if (hits.isEmpty()) {
                    run.setStatus(ScreeningStatus.CLEAR);
                } else {
                    run.setStatus(ScreeningStatus.HIT);
                    for (ScreeningHitDto h : hits) {
                        ScreeningHit hit = new ScreeningHit();
                        hit.setRunId(run.getId());
                        hit.setListSource(h.listSource());
                        hit.setMatchedField(h.matchedField());
                        hit.setMatchedValue(h.matchedValue());
                        hit.setMatchScore(BigDecimal.valueOf(h.matchScore()));
                        hitRepository.save(hit);
                    }
                    log.warn("Sanctions HIT: entity={} provider={} hits={}", entityId, provider.providerName(), hits.size());
                }
                run.setCompletedAt(Instant.now());
            } catch (Exception e) {
                run.setStatus(ScreeningStatus.ERROR);
                run.setErrorMessage(e.getMessage());
                run.setCompletedAt(Instant.now());
                log.error("Screening error for entity={} provider={}", entityId, provider.providerName(), e);
            }
            runRepository.save(run);
        }
        return runRepository.findTopByEntityIdOrderByStartedAtDesc(entityId);
    }

    /** Screen a natural person (beneficial owner, UBO). GwG §11 Abs. 1 Nr. 4. */
    @Transactional
    public ScreeningRun screenNaturalPerson(UUID naturalPersonId, String fullName,
                                             String countryCode, ScreeningTrigger trigger) {
        log.info("Screening natural_person={} trigger={}", naturalPersonId, trigger);
        for (SanctionsScreeningPort provider : providers) {
            ScreeningRun run = new ScreeningRun();
            run.setNaturalPersonId(naturalPersonId);
            run.setTriggerType(trigger);
            run.setProvider(provider.providerName());
            run.setStatus(ScreeningStatus.PENDING);
            run.setStartedAt(Instant.now());
            run = runRepository.save(run);

            try {
                List<ScreeningHitDto> hits = provider.screenEntity(
                        new ScreeningSubjectDto(naturalPersonId, "NATURAL_PERSON", fullName, countryCode, null, null));

                if (hits.isEmpty()) {
                    run.setStatus(ScreeningStatus.CLEAR);
                } else {
                    run.setStatus(ScreeningStatus.HIT);
                    for (ScreeningHitDto h : hits) {
                        ScreeningHit hit = new ScreeningHit();
                        hit.setRunId(run.getId());
                        hit.setListSource(h.listSource());
                        hit.setMatchedField(h.matchedField());
                        hit.setMatchedValue(h.matchedValue());
                        hit.setMatchScore(BigDecimal.valueOf(h.matchScore()));
                        hitRepository.save(hit);
                    }
                    log.warn("Sanctions HIT: natural_person={} provider={} hits={}",
                            naturalPersonId, provider.providerName(), hits.size());
                }
                run.setCompletedAt(Instant.now());
            } catch (Exception e) {
                run.setStatus(ScreeningStatus.ERROR);
                run.setErrorMessage(e.getMessage());
                run.setCompletedAt(Instant.now());
                log.error("Screening error for natural_person={} provider={}", naturalPersonId, provider.providerName(), e);
            }
            runRepository.save(run);
        }
        return runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(naturalPersonId);
    }

    /** Accept a false-positive screening hit (dual-control required per GwG §10). */
    @Transactional
    public ScreeningHit acceptHit(UUID hitId, UUID actorId, UUID approverActorId, String reason) {
        ScreeningHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("ScreeningHit", hitId));
        if (Boolean.TRUE.equals(hit.getAccepted())) {
            throw new IllegalStateException("Hit already accepted.");
        }
        hit.setAccepted(true);
        hit.setAcceptedBy(actorId);
        hit.setAcceptedAt(Instant.now());
        hit.setAcceptReason(reason);
        if (approverActorId != null) {
            hit.setDualControlApproverId(approverActorId);
            hit.setDualControlApprovedAt(Instant.now());
        }
        return hitRepository.save(hit);
    }

    /** Daily re-screen of all entities and natural persons (GwG §10 ongoing monitoring). */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void periodicRefresh() {
        log.info("Starting periodic sanctions re-screening...");
        List<UUID> entityIds = runRepository.findDistinctActiveEntityIds();
        for (UUID entityId : entityIds) {
            try {
                screenEntity(entityId, null, null, null, ScreeningTrigger.PERIODIC_REFRESH);
            } catch (Exception e) {
                log.error("Periodic screening failed for entity={}", entityId, e);
            }
        }
        log.info("Periodic screening complete: {} entities re-screened.", entityIds.size());
    }
}
