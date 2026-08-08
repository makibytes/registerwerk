package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.screening.api.NaturalPersonScreeningSubjectResolver;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningHitDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import de.makibytes.registerwerk.screening.events.SanctionsHitAcceptedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import java.math.BigDecimal;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    /** Hits at or above this match score require a second approver (four-eyes principle). */
    static final java.math.BigDecimal DUAL_CONTROL_SCORE_THRESHOLD = new java.math.BigDecimal("0.80");

    private static final Duration RECENT_ERROR_WINDOW = Duration.ofHours(24);

    private final List<SanctionsScreeningPort> providers;
    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;
    private final ApplicationEventPublisher events;
    private final LegalEntityRepository legalEntityRepository;
    private final NaturalPersonScreeningSubjectResolver naturalPersonResolver;

    ScreeningService(List<SanctionsScreeningPort> providers,
                     ScreeningRunRepository runRepository,
                     ScreeningHitRepository hitRepository,
                     ApplicationEventPublisher events,
                     LegalEntityRepository legalEntityRepository,
                     MeterRegistry meterRegistry,
                     NaturalPersonScreeningSubjectResolver naturalPersonResolver) {
        // Live query at scrape time, like the chain-drift gauge — a hit sitting open too long is
        // exactly the GwG §10 timeliness risk this metric exists to catch.
        Gauge.builder("registerwerk_sanctions_oldest_open_hit_seconds", hitRepository,
                        repo -> repo.findFirstByAcceptedIsNullOrderByCreatedAtAsc()
                                .map(hit -> (double) Duration.between(hit.getCreatedAt(), Instant.now()).getSeconds())
                                .orElse(0.0))
                .description("Age in seconds of the longest-unresolved open sanctions/PEP hit; 0 if none open")
                .register(meterRegistry);
        // Distinct from the gauge above: this tracks provider-call FAILURES (ScreeningStatus.ERROR,
        // never rethrown — ScreeningGateImpl fails closed on it, silently blocking new-entity
        // approvals), not unresolved hits. Repo-wide alerting-gap follow-up.
        Gauge.builder("registerwerk_screening_errors_recent_total", runRepository,
                        repo -> (double) repo.countByStatusAndStartedAtAfter(
                                ScreeningStatus.ERROR, Instant.now().minus(RECENT_ERROR_WINDOW)))
                .description("Count of ScreeningRun rows with status=ERROR in the last 24h")
                .register(meterRegistry);
        this.providers = providers;
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
        this.events = events;
        this.legalEntityRepository = legalEntityRepository;
        this.naturalPersonResolver = naturalPersonResolver;
    }

    @Transactional
    public ScreeningRun screenRegisteredEntity(UUID entityId, ScreeningTrigger trigger) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        return screenEntity(entityId, entity.getCurrentName(), entity.getRegistrationCountry(),
                entity.getLeiCode(), trigger);
    }

    @Transactional
    public ScreeningRun screenEntity(UUID entityId, String entityName, String countryCode,
                                     String lei, ScreeningTrigger trigger) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("Entity name is required for screening");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("Screening trigger is required");
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("No sanctions-screening provider is configured");
        }
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
                        hit.setCategory(parseCategory(h.category()));
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
    public ScreeningRun screenRegisteredNaturalPerson(UUID naturalPersonId, ScreeningTrigger trigger) {
        NaturalPersonScreeningSubjectResolver.NaturalPersonScreeningSubject subject = naturalPersonResolver
                .findById(naturalPersonId)
                .orElseThrow(() -> new EntityNotFoundException("NaturalPerson", naturalPersonId));
        return screenNaturalPerson(naturalPersonId, subject.fullName(), subject.countryCode(), trigger);
    }

    /** Screen a natural person (beneficial owner, UBO). GwG §11 Abs. 1 Nr. 4. */
    @Transactional
    public ScreeningRun screenNaturalPerson(UUID naturalPersonId, String fullName,
                                             String countryCode, ScreeningTrigger trigger) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Natural-person name is required for screening");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("Screening trigger is required");
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("No sanctions-screening provider is configured");
        }
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
                        hit.setCategory(parseCategory(h.category()));
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

    /**
     * Accept a false-positive screening hit.
     *
     * <p>Enforced controls (GwG §8 documentation duty, four-eyes principle):
     * <ul>
     *   <li>a non-blank reason is always mandatory,</li>
     *   <li>hits with match score ≥ {@link #DUAL_CONTROL_SCORE_THRESHOLD} require a
     *       second approver,</li>
     *   <li>the approver must be a different user than the accepting officer.</li>
     * </ul>
     */
    @Transactional
    public ScreeningHit acceptHit(UUID hitId, UUID actorId, String actorRole, UUID approverActorId, String reason) {
        ScreeningHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("ScreeningHit", hitId));
        if (Boolean.TRUE.equals(hit.getAccepted())) {
            throw new IllegalStateException("Hit already accepted.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "A reason is mandatory when accepting a screening hit (GwG §8 documentation duty).");
        }
        boolean dualControlRequired = hit.getMatchScore() != null
                && hit.getMatchScore().compareTo(DUAL_CONTROL_SCORE_THRESHOLD) >= 0;
        if (dualControlRequired && approverActorId == null) {
            throw new ComplianceGateException(
                    "Dual control required: hits with match score >= " + DUAL_CONTROL_SCORE_THRESHOLD +
                    " need a second approver (four-eyes principle).");
        }
        if (approverActorId != null && approverActorId.equals(actorId)) {
            throw new IllegalArgumentException(
                    "Dual-control approver must be a different user than the accepting officer.");
        }
        hit.setAccepted(true);
        hit.setAcceptedBy(actorId);
        hit.setAcceptedAt(Instant.now());
        hit.setAcceptReason(reason);
        if (approverActorId != null) {
            hit.setDualControlApproverId(approverActorId);
            hit.setDualControlApprovedAt(Instant.now());
        }
        ScreeningHit saved = hitRepository.save(hit);

        events.publishEvent(new SanctionsHitAcceptedEvent(hitId, actorId, actorRole, approverActorId,
                Map.of("reason", reason, "matchScore", hit.getMatchScore() != null ? hit.getMatchScore().toString() : "",
                        "dualControlRequired", dualControlRequired)));
        return saved;
    }

    private static HitCategory parseCategory(String category) {
        if (category == null) {
            return HitCategory.SANCTIONS;
        }
        try {
            return HitCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return HitCategory.SANCTIONS;
        }
    }

}
