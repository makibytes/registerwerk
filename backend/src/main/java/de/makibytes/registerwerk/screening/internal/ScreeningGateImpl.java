package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Fail-closed screening gate (GwG §10 Abs. 1 Nr. 5, §11).
 *
 * <p>An approval-blocking condition exists not only when the latest run produced an
 * unresolved HIT, but also when the entity has never been screened, the latest run
 * is still PENDING or rejected, or errored. A screening that did not complete successfully
 * must never be treated as a clear result.
 *
 * <p><strong>Outage grace (6-18, T6-03 interim).</strong> One failed night must not turn every CLEAR
 * subject into a blocked one. A failed run is therefore set aside, and the last good result
 * relied on, for at most {@code stale-clear-grace-hours} after the <em>first</em> failed run of the
 * current outage, and never if that good result itself is older than {@code stale-clear-max-age-hours}.
 * Only subjects that were screened successfully at least once qualify; never-screened subjects
 * stay blocked. A last good result that is itself a HIT with open hits still blocks. After the window
 * the ERROR blocks again. There is no exemption for regulated flows: they use this same gate.
 */
@Component
class ScreeningGateImpl implements ScreeningGate {

    /** How many runs of one subject/provider are inspected to find the outage start and last good result. */
    private static final int HISTORY_LIMIT = 50;

    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;
    private final ScreeningService screeningService;
    private final List<String> providerNames;
    private final ScreeningPolicy policy;
    private final Counter staleReliance;

    ScreeningGateImpl(ScreeningRunRepository runRepository,
                      ScreeningHitRepository hitRepository,
                      ScreeningService screeningService,
                      List<SanctionsScreeningPort> providers,
                      ScreeningPolicy policy,
                      MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
        this.screeningService = screeningService;
        this.policy = policy;
        this.providerNames = providers.stream()
                .map(SanctionsScreeningPort::providerName)
                .distinct()
                .toList();
        this.staleReliance = Counter.builder("registerwerk_screening_stale_reliance_total")
                .description("Gate evaluations answered from the last good result because the latest run failed")
                .register(meterRegistry);
    }

    /** Outcome of evaluating one subject at one provider. */
    record Evaluation(boolean blocked, boolean stale, Instant graceEndsAt, Instant lastGoodRunAt) {
        static Evaluation blocking() { return new Evaluation(true, false, null, null); }
        static Evaluation of(boolean blocked) { return new Evaluation(blocked, false, null, null); }
    }

    @Override
    public void screenNaturalPerson(UUID naturalPersonId, String fullName, String countryCode, ScreeningTrigger trigger) {
        screeningService.screenNaturalPerson(naturalPersonId, fullName, countryCode, trigger);
    }

    @Override
    public boolean hasUnresolvedHit(UUID entityId) {
        return evaluateEntity(entityId).stream().anyMatch(Evaluation::blocked) || providerNames.isEmpty();
    }

    @Override
    public boolean hasUnresolvedHitForPerson(UUID naturalPersonId) {
        return providerNames.isEmpty() || evaluatePerson(naturalPersonId).stream().anyMatch(Evaluation::blocked);
    }

    @Override
    public boolean hasUnresolvedBeneficialOwnerHit(UUID entityId) {
        if (providerNames.isEmpty()) {
            return true;
        }
        for (Object[] row : runRepository.findCurrentBeneficialOwnerPersons(entityId)) {
            UUID personId = (UUID) row[0];
            boolean redacted = Boolean.TRUE.equals(row[1]);
            if (redacted && runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(personId) == null) {
                continue; // erased: nothing left to screen, and nothing was ever screened
            }
            if (evaluatePerson(personId).stream().anyMatch(Evaluation::blocked)) {
                return true;
            }
        }
        // Ceased records stay in the gate while their latest run is unresolved: ceasing is not a way
        // to resolve a hit. A ceased person who was never screened does not block.
        for (UUID personId : runRepository.findCeasedBeneficialOwnerPersonIds(entityId)) {
            if (runRepository.findTopByNaturalPersonIdOrderByStartedAtDesc(personId) != null
                    && evaluatePerson(personId).stream().anyMatch(Evaluation::blocked)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasConfirmedPep(UUID naturalPersonId) {
        for (String provider : providerNames) {
            ScreeningRun latest = runRepository.findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(
                    naturalPersonId, provider);
            if (latest != null && hitRepository.findByRunId(latest.getId()).stream()
                    .anyMatch(h -> h.getResolution() == HitResolution.CONFIRMED_PEP)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int recordPepEddApproval(UUID naturalPersonId, UUID eddApprovalId, Instant reviewDue,
                                    UUID actorId, String actorRole, UUID approverId) {
        return screeningService.recordPepEdd(naturalPersonId, eddApprovalId, reviewDue, actorId, actorRole, approverId);
    }

    @Override
    public boolean isRelyingOnStaleResult(UUID entityId) {
        return evaluateEntity(entityId).stream().anyMatch(e -> e.stale() && !e.blocked());
    }

    /** Per-provider evaluation, exposed to the operator status endpoint. */
    List<Evaluation> evaluateEntity(UUID entityId) {
        Instant now = Instant.now();
        return providerNames.stream()
                .map(provider -> evaluate(
                        runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entityId, provider),
                        page -> runRepository.findByEntityIdAndProviderOrderByStartedAtDesc(entityId, provider, page),
                        now))
                .toList();
    }

    List<Evaluation> evaluatePerson(UUID personId) {
        Instant now = Instant.now();
        return providerNames.stream()
                .map(provider -> evaluate(
                        runRepository.findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(personId, provider),
                        page -> runRepository.findByNaturalPersonIdAndProviderOrderByStartedAtDesc(personId, provider, page),
                        now))
                .toList();
    }

    private Evaluation evaluate(ScreeningRun latest, Function<Pageable, List<ScreeningRun>> history, Instant now) {
        if (latest == null) {
            return Evaluation.blocking(); // never screened — block until a screening run completes
        }
        if (latest.getStatus() != ScreeningStatus.ERROR) {
            return Evaluation.of(blocksApproval(latest, now));
        }
        // Latest run failed: find where the outage started and what the last good result was.
        Instant outageStart = latest.getStartedAt();
        ScreeningRun lastGood = null;
        for (ScreeningRun r : history.apply(PageRequest.of(0, HISTORY_LIMIT))) {
            if (r.getStatus() == ScreeningStatus.ERROR) {
                outageStart = r.getStartedAt();
            } else {
                lastGood = r;
                break;
            }
        }
        if (lastGood == null || lastGood.getStatus() == ScreeningStatus.PENDING
                || lastGood.getStatus() == ScreeningStatus.REJECTED) {
            return Evaluation.blocking(); // never screened successfully
        }
        Instant graceEnds = outageStart.plus(policy.staleClearGrace());
        boolean withinGrace = now.isBefore(graceEnds)
                && lastGood.getStartedAt().isAfter(now.minus(policy.staleClearMaxAge()));
        if (!withinGrace) {
            return Evaluation.blocking();
        }
        boolean blocked = blocksApproval(lastGood, now);
        if (!blocked) {
            staleReliance.increment();
        }
        return new Evaluation(blocked, true, graceEnds, lastGood.getStartedAt());
    }

    /**
     * Fail closed: only a completed CLEAR/ACCEPTED run, or a HIT run whose hits have
     * all been reviewed (or are confirmed PEPs under an unexpired EDD approval), permits approval.
     */
    private boolean blocksApproval(ScreeningRun latest, Instant now) {
        return switch (latest.getStatus()) {
            case CLEAR, ACCEPTED -> false;
            case HIT -> hitRepository.findByRunIdAndAcceptedIsNull(latest.getId()).stream()
                    .anyMatch(h -> h.blocksGate(now));
            case PENDING, ERROR, REJECTED -> true;
        };
    }
}
