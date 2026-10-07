package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.screening.api.NaturalPersonScreeningSubjectResolver;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningHitDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningResult;
import de.makibytes.registerwerk.screening.events.SanctionsHitAcceptedEvent;
import de.makibytes.registerwerk.screening.events.ScreeningHitCarriedForwardEvent;
import de.makibytes.registerwerk.screening.events.ScreeningPepConfirmedEvent;
import de.makibytes.registerwerk.screening.events.ScreeningPepEddRecordedEvent;
import de.makibytes.registerwerk.screening.events.ScreeningProviderDegradedEvent;
import de.makibytes.registerwerk.screening.events.ScreeningHitDetectedEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import java.math.BigDecimal;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates sanctions/PEP screening runs.
 * Trigger points: entity onboarding, KYC submission, beneficial-owner add,
 * ERC-3643 claim issuance, and daily periodic refresh (GwG §10 Abs. 1 Nr. 5).
 *
 * <p>Re-screens do not start from zero: a match that was already accepted as a false positive by a
 * dual-controlled decision is recognised by its fingerprint and inherits that decision for a bounded
 * validity window, each time with an audit entry (6-18).
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
    private final ScreeningPolicy policy;

    ScreeningService(List<SanctionsScreeningPort> providers,
                     ScreeningRunRepository runRepository,
                     ScreeningHitRepository hitRepository,
                     ApplicationEventPublisher events,
                     LegalEntityRepository legalEntityRepository,
                     MeterRegistry meterRegistry,
                     NaturalPersonScreeningSubjectResolver naturalPersonResolver,
                     ScreeningPolicy policy) {
        // Live query at scrape time, like the chain-drift gauge — a hit sitting open too long is
        // exactly the GwG §10 timeliness risk this metric exists to catch. A confirmed PEP under an
        // approved, unexpired EDD record is deliberately not "open" any more.
        Gauge.builder("registerwerk_sanctions_oldest_open_hit_seconds", hitRepository,
                        repo -> repo.findOldestBlockingHits(Instant.now(), PageRequest.of(0, 1)).stream().findFirst()
                                .map(hit -> (double) Duration.between(hit.getCreatedAt(), Instant.now()).getSeconds())
                                .orElse(0.0))
                .description("Age in seconds of the longest-unresolved open sanctions/PEP hit; 0 if none open")
                .register(meterRegistry);
        // Distinct from the gauge above: this tracks provider-call FAILURES (ScreeningStatus.ERROR,
        // never rethrown — ScreeningGateImpl fails closed on it once the grace window is over), not
        // unresolved hits.
        Gauge.builder("registerwerk_screening_errors_recent_total", runRepository,
                        repo -> (double) repo.countByStatusAndStartedAtAfter(
                                ScreeningStatus.ERROR, Instant.now().minus(RECENT_ERROR_WINDOW)))
                .description("Count of ScreeningRun rows with status=ERROR in the last 24h")
                .register(meterRegistry);
        // Degraded state (6-18): subjects whose newest run failed, and the subset that still has a
        // recent good result the gate may be relying on during the grace window.
        Gauge.builder("registerwerk_screening_degraded_subjects", runRepository,
                        repo -> (double) repo.countLatestError())
                .description("Subject/provider pairs whose latest screening run ended in ERROR")
                .register(meterRegistry);
        Gauge.builder("registerwerk_screening_stale_results", runRepository,
                        repo -> (double) repo.countLatestErrorWithRecentGoodResult(
                                Instant.now().minus(policy.staleClearMaxAge())))
                .description("Subject/provider pairs whose latest run failed but that still have a recent good "
                        + "result (gate relies on it only inside the grace window)")
                .register(meterRegistry);
        this.providers = providers;
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
        this.events = events;
        this.legalEntityRepository = legalEntityRepository;
        this.naturalPersonResolver = naturalPersonResolver;
        this.policy = policy;
    }

    @Transactional
    public ScreeningRun screenRegisteredEntity(UUID entityId, ScreeningTrigger trigger) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
        return screenEntity(entityId, entity.getCurrentName(), entity.getRegistrationCountry(),
                entity.getLeiCode(), entity.getRegistrationNumber(), trigger);
    }

    @Transactional
    public ScreeningRun screenEntity(UUID entityId, String entityName, String countryCode,
                                     String lei, ScreeningTrigger trigger) {
        return screenEntity(entityId, entityName, countryCode, lei, null, trigger);
    }

    @Transactional
    public ScreeningRun screenEntity(UUID entityId, String entityName, String countryCode,
                                     String lei, String registrationNumber, ScreeningTrigger trigger) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("Entity name is required for screening");
        }
        requireRunnable(trigger);
        log.info("Screening entity={} trigger={}", entityId, trigger);
        ScreeningSubjectDto subject = new ScreeningSubjectDto(
                entityId, "LEGAL_ENTITY", entityName, countryCode, lei, registrationNumber, null, null);
        for (SanctionsScreeningPort provider : providers) {
            ScreeningRun run = newRun(trigger, provider, entityId, null);
            screenWith(provider, run, subject, trigger);
        }
        return runRepository.findTopByEntityIdOrderByStartedAtDesc(entityId);
    }

    /** Screen a natural person (beneficial owner, UBO). GwG §11 Abs. 1 Nr. 4. */
    @Transactional
    public ScreeningRun screenRegisteredNaturalPerson(UUID naturalPersonId, ScreeningTrigger trigger) {
        NaturalPersonScreeningSubjectResolver.NaturalPersonScreeningSubject subject = naturalPersonResolver
                .findById(naturalPersonId)
                .orElseThrow(() -> new EntityNotFoundException("NaturalPerson", naturalPersonId));
        return screenNaturalPerson(naturalPersonId, subject.fullName(), subject.countryCode(),
                subject.dateOfBirth(), subject.nationality(), trigger);
    }

    /**
     * Screen a natural person (beneficial owner, UBO). GwG §11 Abs. 1 Nr. 4. Date of birth and
     * nationality are looked up from the canonical KYC record so every caller sends them.
     */
    @Transactional
    public ScreeningRun screenNaturalPerson(UUID naturalPersonId, String fullName,
                                             String countryCode, ScreeningTrigger trigger) {
        var known = naturalPersonResolver.findById(naturalPersonId);
        return screenNaturalPerson(naturalPersonId, fullName, countryCode,
                known.map(NaturalPersonScreeningSubjectResolver.NaturalPersonScreeningSubject::dateOfBirth).orElse(null),
                known.map(NaturalPersonScreeningSubjectResolver.NaturalPersonScreeningSubject::nationality).orElse(null),
                trigger);
    }

    @Transactional
    public ScreeningRun screenNaturalPerson(UUID naturalPersonId, String fullName, String countryCode,
                                            LocalDate dateOfBirth, String nationality, ScreeningTrigger trigger) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Natural-person name is required for screening");
        }
        requireRunnable(trigger);
        log.info("Screening natural_person={} trigger={}", naturalPersonId, trigger);
        ScreeningSubjectDto subject = new ScreeningSubjectDto(
                naturalPersonId, "NATURAL_PERSON", fullName, countryCode, null, null, dateOfBirth, nationality);
        for (SanctionsScreeningPort provider : providers) {
            ScreeningRun run = newRun(trigger, provider, null, naturalPersonId);
            screenWith(provider, run, subject, trigger);
        }
        return runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(naturalPersonId);
    }

    private void requireRunnable(ScreeningTrigger trigger) {
        if (trigger == null) {
            throw new IllegalArgumentException("Screening trigger is required");
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("No sanctions-screening provider is configured");
        }
    }

    private ScreeningRun newRun(ScreeningTrigger trigger, SanctionsScreeningPort provider,
                                UUID entityId, UUID naturalPersonId) {
        ScreeningRun run = new ScreeningRun();
        run.setEntityId(entityId);
        run.setNaturalPersonId(naturalPersonId);
        run.setTriggerType(trigger);
        run.setProvider(provider.providerName());
        run.setStatus(ScreeningStatus.PENDING);
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    private void screenWith(SanctionsScreeningPort provider, ScreeningRun run, ScreeningSubjectDto subject,
                            ScreeningTrigger trigger) {
        try {
            ScreeningResult result = provider.screen(subject);
            run.setThresholdUsed(result.thresholdUsed());
            run.setDataVersion(result.dataVersion());
            if (result.hits().isEmpty()) {
                run.setStatus(ScreeningStatus.CLEAR);
            } else {
                recordHits(run, subject, provider.providerName(), result.hits(), trigger);
            }
            run.setCompletedAt(Instant.now());
        } catch (Exception e) {
            run.setStatus(ScreeningStatus.ERROR);
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(Instant.now());
            log.error("Screening error for {}={} provider={}", subject.subjectType(), subject.subjectId(),
                    provider.providerName(), e);
        }
        runRepository.save(run);
    }

    /**
     * Stores the hits of a run. A hit that matches a previously accepted false positive (see
     * {@link #findCarryForwardSource}) is created already accepted and audited; everything else stays
     * open and fires {@link ScreeningHitDetectedEvent}. If every hit was carried the run is ACCEPTED.
     */
    private void recordHits(ScreeningRun run, ScreeningSubjectDto subject, String provider,
                            List<ScreeningHitDto> hits, ScreeningTrigger trigger) {
        List<ScreeningHitDto> reportable = new java.util.ArrayList<>();
        boolean anyBlocking = false;
        Instant now = Instant.now();
        String subjectDigest = HitFingerprint.subjectDigest(subject);
        for (ScreeningHitDto h : hits) {
            ScreeningHit hit = new ScreeningHit();
            hit.setRunId(run.getId());
            hit.setListSource(h.listSource());
            hit.setMatchedField(h.matchedField());
            hit.setMatchedValue(h.matchedValue());
            hit.setMatchScore(BigDecimal.valueOf(h.matchScore()));
            hit.setCategory(parseCategory(h.category()));
            hit.setExternalId(h.externalId());
            hit.setFingerprint(HitFingerprint.of(provider, h.listSource(), h.externalId(), h.matchedValue(),
                    h.recordVersion(), subjectDigest));

            ScreeningHit source = findCarryForwardSource(subject, hit, now);
            if (source != null) {
                inheritDecision(hit, source, now);
            }
            hitRepository.save(hit);
            if (source != null) {
                events.publishEvent(new ScreeningHitCarriedForwardEvent(hit.getId(), subject.subjectType(),
                        subject.subjectId(), carryPayload(run, hit, source)));
            } else {
                reportable.add(h);
            }
            anyBlocking |= hit.blocksGate(now);
        }
        run.setStatus(anyBlocking ? ScreeningStatus.HIT : ScreeningStatus.ACCEPTED);
        log.warn("Sanctions HIT: {}={} provider={} hits={} newOrReopened={}", subject.subjectType(),
                subject.subjectId(), provider, hits.size(), reportable.size());
        if (!reportable.isEmpty()) {
            publishHitDetected(run, subject.subjectType(), subject.subjectId(), provider, trigger, reportable);
        }
    }

    /**
     * The original decision a new hit may inherit, or null. Carried only when ALL hold: same subject
     * and fingerprint (the fingerprint covers the provider record's version and the subject's identifying
     * data, so a changed list entry or a changed subject never inherits); the original is a false-positive acceptance with an accepting officer AND a
     * second approver on file (SRE veto); it is younger than {@code accept-validity-days} counted from
     * the original decision; same category; and the new score is not above the original score plus
     * the tolerance. A confirmed PEP is inherited as a confirmation only (the hit stays unresolved).
     */
    private ScreeningHit findCarryForwardSource(ScreeningSubjectDto subject, ScreeningHit hit, Instant now) {
        if (hit.getFingerprint() == null || hit.getMatchScore() == null) {
            return null;
        }
        if (hit.getCategory() == HitCategory.PEP && "NATURAL_PERSON".equals(subject.subjectType())) {
            ScreeningHit pep = hitRepository.findConfirmedPepSources(subject.subjectId(), hit.getFingerprint(),
                    HitResolution.CONFIRMED_PEP, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
            if (pep != null) {
                return pep;
            }
        }
        ScreeningHit source = hitRepository.findCarryForwardSources(subject.subjectId(), hit.getFingerprint(),
                HitResolution.FALSE_POSITIVE, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (source == null || source.getAcceptedAt() == null || source.getMatchScore() == null
                || source.getCategory() != hit.getCategory()) {
            return null;
        }
        if (source.getAcceptedAt().isBefore(now.minus(Duration.ofDays(policy.acceptValidityDays())))) {
            return null;
        }
        if (hit.getMatchScore().compareTo(source.getMatchScore().add(policy.scoreTolerance())) > 0) {
            return null;
        }
        return source;
    }

    private void inheritDecision(ScreeningHit hit, ScreeningHit source, Instant now) {
        hit.setCarriedFromHitId(source.getId());
        hit.setCarriedAt(now);
        hit.setResolution(source.getResolution());
        hit.setDualControlApproverId(source.getDualControlApproverId());
        hit.setDualControlApprovedAt(source.getDualControlApprovedAt());
        if (source.getResolution() == HitResolution.CONFIRMED_PEP) {
            // A confirmation is carried; the hit itself stays unresolved and keeps the gate closed
            // unless the EDD cover recorded on the source is still in force.
            hit.setPepConfirmedBy(source.getPepConfirmedBy());
            hit.setPepConfirmedAt(source.getPepConfirmedAt());
            hit.setPepConfirmNote(source.getPepConfirmNote());
            hit.setEddApprovalId(source.getEddApprovalId());
            hit.setEddApprovedAt(source.getEddApprovedAt());
            hit.setEddReviewDue(source.getEddReviewDue());
        } else {
            hit.setAccepted(true);
            hit.setAcceptedBy(source.getAcceptedBy());
            hit.setAcceptedAt(source.getAcceptedAt());   // original decision time: the validity window never slides
            hit.setAcceptReason(source.getAcceptReason());
        }
    }

    private Map<String, Object> carryPayload(ScreeningRun run, ScreeningHit hit, ScreeningHit source) {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("runId", String.valueOf(run.getId()));
        p.put("originalHitId", String.valueOf(source.getId()));
        p.put("resolution", String.valueOf(source.getResolution()));
        p.put("fingerprint", hit.getFingerprint());
        p.put("matchScore", String.valueOf(hit.getMatchScore()));
        p.put("originalMatchScore", String.valueOf(source.getMatchScore()));
        p.put("originalAcceptedBy", String.valueOf(source.getAcceptedBy() != null ? source.getAcceptedBy() : source.getPepConfirmedBy()));
        p.put("originalApproverId", String.valueOf(source.getDualControlApproverId()));
        p.put("originalDecidedAt", String.valueOf(source.getAcceptedAt() != null ? source.getAcceptedAt() : source.getPepConfirmedAt()));
        if (source.getResolution() == HitResolution.FALSE_POSITIVE && source.getAcceptedAt() != null) {
            p.put("validUntil", source.getAcceptedAt().plus(Duration.ofDays(policy.acceptValidityDays())).toString());
        }
        return p;
    }

    /**
     * Publishes the (still unreviewed) hit for audit and for a future on-chain response — see
     * {@link ScreeningHitDetectedEvent}; the automatic response itself is a parked decision.
     */
    private void publishHitDetected(ScreeningRun run, String subjectType, UUID subjectId, String provider,
                                    ScreeningTrigger trigger, List<ScreeningHitDto> hits) {
        double maxScore = hits.stream().mapToDouble(ScreeningHitDto::matchScore).max().orElse(0.0);
        List<String> listSources = hits.stream().map(ScreeningHitDto::listSource)
                .filter(java.util.Objects::nonNull).distinct().toList();
        events.publishEvent(new ScreeningHitDetectedEvent(run.getId(), subjectType, subjectId, Map.of(
                "provider", provider,
                "trigger", trigger.name(),
                "hitCount", hits.size(),
                "maxMatchScore", maxScore,
                "listSources", listSources)));
    }

    /** Audited operational alert: a screening batch left subjects without a fresh result. */
    @Transactional
    public void reportDegraded(String reason, Map<String, Object> details) {
        log.error("SCREENING PROVIDER DEGRADED: {} {}", reason, details);
        events.publishEvent(new ScreeningProviderDegradedEvent(reason, details));
    }

    /**
     * Accept a false-positive screening hit.
     *
     * <p>Enforced controls (GwG §8 documentation duty, four-eyes principle):
     * <ul>
     *   <li>a non-blank reason is always mandatory,</li>
     *   <li>hits with match score ≥ {@link #DUAL_CONTROL_SCORE_THRESHOLD} require a
     *       second approver,</li>
     *   <li>the approver must be a different user than the accepting officer,</li>
     *   <li>a hit confirmed as a PEP is not a false positive and cannot be accepted as one.</li>
     * </ul>
     */
    @Transactional
    public ScreeningHit acceptHit(UUID hitId, UUID actorId, String actorRole, UUID approverActorId, String reason) {
        ScreeningHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("ScreeningHit", hitId));
        if (Boolean.TRUE.equals(hit.getAccepted())) {
            throw new IllegalStateException("Hit already accepted.");
        }
        if (hit.getResolution() == HitResolution.CONFIRMED_PEP) {
            throw new InvalidStateTransitionException(
                    "Hit was confirmed as a PEP; it cannot be accepted as a false positive. "
                            + "Record an EDD approval for the person instead.");
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
        hit.setResolution(HitResolution.FALSE_POSITIVE);
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
                        "category", String.valueOf(hit.getCategory()),
                        "resolution", HitResolution.FALSE_POSITIVE.name(),
                        "dualControlRequired", dualControlRequired)));
        return saved;
    }

    /**
     * Confirm a PEP hit on a natural person (6-17). This is the opposite of a false-positive
     * acceptance: the hit stays unresolved for the screening gate until an EDD approval is recorded
     * ({@link #recordPepEdd}), and the kyc module is told so it can set the person's PEP status.
     * Always needs a second approver and a note; only PEP-category hits of natural persons qualify
     * (a sanctions hit can never be "confirmed away" into an EDD path).
     */
    @Transactional
    public ScreeningHit confirmPep(UUID hitId, UUID actorId, String actorRole, UUID approverActorId, String note) {
        ScreeningHit hit = hitRepository.findById(hitId)
                .orElseThrow(() -> new EntityNotFoundException("ScreeningHit", hitId));
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A note is mandatory when confirming a PEP hit.");
        }
        if (approverActorId == null) {
            throw new ComplianceGateException("Dual control required: confirming a PEP needs a second approver.");
        }
        if (approverActorId.equals(actorId)) {
            throw new IllegalArgumentException(
                    "Dual-control approver must be a different user than the confirming officer.");
        }
        if (Boolean.TRUE.equals(hit.getAccepted()) || hit.getResolution() != null) {
            throw new InvalidStateTransitionException("Hit is already resolved (" + hit.getResolution() + ").");
        }
        if (hit.getCategory() != HitCategory.PEP) {
            throw new InvalidStateTransitionException(
                    "Only PEP-category hits can be confirmed as a PEP (this hit is " + hit.getCategory() + ").");
        }
        ScreeningRun run = runRepository.findById(hit.getRunId())
                .orElseThrow(() -> new EntityNotFoundException("ScreeningRun", hit.getRunId()));
        if (run.getNaturalPersonId() == null) {
            throw new InvalidStateTransitionException("Only hits on natural persons can be confirmed as a PEP.");
        }
        Instant now = Instant.now();
        hit.setResolution(HitResolution.CONFIRMED_PEP);
        hit.setPepConfirmedBy(actorId);
        hit.setPepConfirmedAt(now);
        hit.setPepConfirmNote(note);
        hit.setDualControlApproverId(approverActorId);
        hit.setDualControlApprovedAt(now);
        ScreeningHit saved = hitRepository.save(hit);
        events.publishEvent(new ScreeningPepConfirmedEvent(hitId, run.getNaturalPersonId(), actorId, actorRole,
                approverActorId, Map.of(
                "note", note,
                "naturalPersonId", run.getNaturalPersonId().toString(),
                "matchScore", hit.getMatchScore() != null ? hit.getMatchScore().toString() : "")));
        return saved;
    }

    /**
     * Records that enhanced due diligence for a confirmed PEP was approved (the approval itself,
     * its four-eyes control and its review cadence belong to the kyc module). From now until
     * {@code reviewDue} the person's confirmed-PEP hits no longer hold the gate closed; afterwards they
     * do again. Throws if there is no confirmed PEP.
     *
     * @return number of hits covered
     */
    @Transactional
    public int recordPepEdd(UUID naturalPersonId, UUID eddApprovalId, Instant reviewDue,
                            UUID actorId, String actorRole, UUID approverActorId) {
        Instant now = Instant.now();
        if (eddApprovalId == null || reviewDue == null || !reviewDue.isAfter(now)) {
            throw new IllegalArgumentException("An EDD approval id and a future review date are required.");
        }
        if (approverActorId == null || approverActorId.equals(actorId)) {
            throw new ComplianceGateException("EDD approval needs a second approver different from the actor.");
        }
        int covered = 0;
        // Every unresolved confirmed-PEP hit of the person, including copies on superseded runs, so the
        // open-hit queue and the age gauge do not keep reporting a person whose EDD is in force.
        for (ScreeningHit hit : hitRepository.findUnresolvedConfirmedPepHits(naturalPersonId)) {
            hit.setEddApprovalId(eddApprovalId);
            hit.setEddApprovedAt(now);
            hit.setEddReviewDue(reviewDue);
            hitRepository.save(hit);
            covered++;
        }
        if (covered == 0) {
            throw new ComplianceGateException("No confirmed-PEP screening hit exists for this person; "
                    + "confirm the PEP hit before recording EDD.");
        }
        events.publishEvent(new ScreeningPepEddRecordedEvent(naturalPersonId, actorId, actorRole, approverActorId,
                Map.of("eddApprovalId", eddApprovalId.toString(), "reviewDue", reviewDue.toString(),
                        "hitsCovered", covered)));
        return covered;
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
