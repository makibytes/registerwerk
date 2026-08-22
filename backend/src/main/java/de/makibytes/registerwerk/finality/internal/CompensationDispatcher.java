package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.events.ChainEffectCompensatedEvent;
import de.makibytes.registerwerk.finality.events.CompensationEscalatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Discovers every {@link ChainEffectCompensator} bean via Spring {@code List<...>} collection
 * injection (the {@code ScreeningGateImpl} inversion — see {@code ChainEffectCompensator}'s
 * javadoc) and drives one {@code chain_effect} row through claim → compensate → resolve.
 *
 * <p>Fails closed three distinct ways, each a {@link CompensationEscalatedEvent}, never only a log
 * line: no compensator registered for the row's {@code effectType}; the compensator returned
 * {@link CompensationOutcome.Failed}; or it returned {@link CompensationOutcome.Irreversible}.
 * {@link ChainEffectRetryJob} re-attempts {@code COMPENSATION_FAILED} rows on a schedule;
 * {@code IRREVERSIBLE_ESCALATED} rows are never retried automatically.
 */
@Component
class CompensationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CompensationDispatcher.class);

    private final ChainEffectRepository repository;
    private final Map<String, ChainEffectCompensator> compensatorsByEffectType;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter compensatedCounter;
    private final Counter notApplicableCounter;
    private final Counter escalatedCounter;

    CompensationDispatcher(ChainEffectRepository repository, List<ChainEffectCompensator> compensators,
            ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.compensatorsByEffectType = compensators.stream()
                .collect(Collectors.toMap(ChainEffectCompensator::effectType, c -> c, (a, b) -> {
                    throw new IllegalStateException(
                            "Two ChainEffectCompensator beans registered for the same effectType: " + a.effectType());
                }));
        this.eventPublisher = eventPublisher;
        this.compensatedCounter = Counter.builder("registerwerk.compensation.outcome")
                .tag("outcome", "compensated")
                .description("Chain effects successfully undone")
                .register(meterRegistry);
        this.notApplicableCounter = Counter.builder("registerwerk.compensation.outcome")
                .tag("outcome", "not_applicable")
                .description("Chain effects the compensator found already undone")
                .register(meterRegistry);
        this.escalatedCounter = Counter.builder("registerwerk.compensation.escalated")
                .description("Chain effects that did not resolve cleanly - no compensator, failure, or irreversible")
                .register(meterRegistry);
        log.info("CompensationDispatcher: {} compensator(s) registered: {}",
                this.compensatorsByEffectType.size(), this.compensatorsByEffectType.keySet());
    }

    /** Looks up the compensator registered for {@code effectType}, if any — used by
     *  {@code ChainEffectRecorderImpl} to validate a descriptor's category against its
     *  compensator's declared category at journal time (fail fast at write time rather than only
     *  discovering a mismatch when compensation actually runs). */
    Optional<ChainEffectCompensator> compensatorFor(String effectType) {
        return Optional.ofNullable(compensatorsByEffectType.get(effectType));
    }

    /** How long a row may sit in {@code COMPENSATING} before it's considered abandoned (the JVM
     *  that claimed it died mid-compensate) and becomes reclaimable again — without this, a crash
     *  mid-compensation leaves the row stuck forever, since {@code claimForCompensation} otherwise
     *  only reclaims {@code ACTIVE}/{@code COMPENSATION_FAILED}. */
    static final java.time.Duration STUCK_COMPENSATING_RECLAIM_AFTER = java.time.Duration.ofMinutes(5);

    /** Atomically claims {@code chainEffectId} (no-op, returning {@link CompensationOutcome.NotApplicable}
     *  if it is not currently claimable - already resolved, already being processed by another
     *  dispatcher, or stuck {@code COMPENSATING} for less than {@link #STUCK_COMPENSATING_RECLAIM_AFTER})
     *  and drives it through its registered compensator. */
    @Transactional
    CompensationOutcome compensate(UUID chainEffectId) {
        Instant staleBefore = Instant.now().minus(STUCK_COMPENSATING_RECLAIM_AFTER);
        if (repository.claimForCompensation(chainEffectId, staleBefore) == 0) {
            return new CompensationOutcome.NotApplicable(
                    "chain_effect " + chainEffectId + " was not claimable (already resolved or in progress)");
        }

        ChainEffect row = repository.findById(chainEffectId)
                .orElseThrow(() -> new IllegalStateException(
                        "chain_effect " + chainEffectId + " vanished between claim and load"));

        ChainEffectCompensator compensator = compensatorsByEffectType.get(row.getEffectType());
        if (compensator == null) {
            String reason = "No ChainEffectCompensator registered for effectType=" + row.getEffectType();
            row.setAttemptCount(row.getAttemptCount() + 1);
            row.setResolutionDetail(reason);
            row.setStatus(ChainEffect.Status.COMPENSATION_FAILED);
            repository.save(row);
            escalate(row, CompensationEscalatedEvent.Reason.NO_COMPENSATOR, reason);
            return new CompensationOutcome.Failed(reason, null);
        }

        CompensationOutcome outcome;
        try {
            outcome = compensator.compensate(toRecord(row));
        } catch (Exception e) {
            outcome = new CompensationOutcome.Failed("Compensator threw: " + e.getMessage(), e);
        }

        row.setAttemptCount(row.getAttemptCount() + 1);
        resolve(row, outcome);
        return outcome;
    }

    private void resolve(ChainEffect row, CompensationOutcome outcome) {
        switch (outcome) {
            case CompensationOutcome.Compensated compensated -> {
                row.setStatus(ChainEffect.Status.COMPENSATED);
                row.setCompensatedAt(Instant.now());
                row.setResolutionDetail(compensated.detail());
                repository.save(row);
                eventPublisher.publishEvent(new ChainEffectCompensatedEvent(
                        row.getId(), row.getEffectType(), row.getEntityType(), row.getEntityId(),
                        row.getAuditEventId(), compensated.detail(), Instant.now()));
                compensatedCounter.increment();
            }
            case CompensationOutcome.NotApplicable notApplicable -> {
                row.setStatus(ChainEffect.Status.COMPENSATED);
                row.setCompensatedAt(Instant.now());
                row.setResolutionDetail(notApplicable.reason());
                repository.save(row);
                notApplicableCounter.increment();
            }
            case CompensationOutcome.Failed failed -> {
                row.setStatus(ChainEffect.Status.COMPENSATION_FAILED);
                row.setResolutionDetail(failed.reason());
                repository.save(row);
                escalate(row, CompensationEscalatedEvent.Reason.FAILED, failed.reason());
            }
            case CompensationOutcome.Irreversible irreversible -> {
                row.setStatus(ChainEffect.Status.IRREVERSIBLE_ESCALATED);
                row.setResolutionDetail(irreversible.remediationHint());
                repository.save(row);
                escalate(row, CompensationEscalatedEvent.Reason.IRREVERSIBLE, irreversible.remediationHint());
            }
        }
    }

    private void escalate(ChainEffect row, CompensationEscalatedEvent.Reason reason, String detail) {
        eventPublisher.publishEvent(new CompensationEscalatedEvent(
                row.getId(), row.getEffectType(), row.getEntityType(), row.getEntityId(), reason, detail, Instant.now()));
        escalatedCounter.increment();
    }

    /** Startup self-check: every {@code effect_type} still {@code ACTIVE} in the journal must have
     *  a registered compensator, or a retraction touching it can never be resolved. Logs, rather
     *  than fails startup, since a gap here is a deploy-ordering issue (a compensator not yet
     *  shipped for a brand-new effect type) that should be loud but must not take the whole
     *  registry down. */
    @EventListener(ApplicationReadyEvent.class)
    void verifyEveryActiveEffectTypeHasACompensator() {
        for (String effectType : repository.findDistinctActiveEffectTypes()) {
            if (!compensatorsByEffectType.containsKey(effectType)) {
                log.error("CompensationDispatcher: effect_type={} has ACTIVE chain_effect rows but no "
                        + "registered ChainEffectCompensator - a retraction touching these can never be "
                        + "compensated until one is added.", effectType);
            }
        }
    }

    private static ChainEffectRecord toRecord(ChainEffect row) {
        return new ChainEffectRecord(
                row.getId(), row.getChainConfigId(), row.getBlockNumber(), row.getBlockHash(),
                row.getTxHash(), row.getLogIndex(), row.getModuleName(), row.getEffectType(),
                row.getEntityType(), row.getEntityId(), row.getAssetId(), row.getCategory(), row.getBeforeState(),
                row.getAfterState(), row.getAuditEventId(), row.getCorrelationId(),
                row.getStatus().name(), row.getAttemptCount(), row.getRecordedAt());
    }
}
