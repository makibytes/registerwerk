package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChaincacheInboxRecoveryPort;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.events.ChainEffectAcknowledgedEvent;
import de.makibytes.registerwerk.finality.events.ChainQuarantineResolvedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin operations on the effect journal — called by {@code finality.web.FinalityJournalController}
 * (same module, different subpackage, mirroring {@code FinalityPolicyAdminService}'s equivalent
 * relationship to {@code FinalityPolicyController}). Backs the operator "unresolved compensation"
 * queue: every {@code chain_effect} row that failed or was escalated as irreversible blocks the
 * affected asset via {@code FinalityGateImpl} until acknowledged here.
 */
@Service
public class FinalityJournalAdminService {

    private static final Logger log = LoggerFactory.getLogger(FinalityJournalAdminService.class);

    private static final List<ChainEffect.Status> UNRESOLVED_STATUSES =
            List.of(ChainEffect.Status.COMPENSATION_FAILED, ChainEffect.Status.IRREVERSIBLE_ESCALATED);

    private final ChainEffectRepository repository;
    private final CompensationDispatcher dispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final ChainQuarantineStore quarantineStore;
    private final ChaincacheInboxRecoveryPort inboxRecovery;

    FinalityJournalAdminService(ChainEffectRepository repository, CompensationDispatcher dispatcher,
            ApplicationEventPublisher eventPublisher, ChainQuarantineStore quarantineStore,
            ChaincacheInboxRecoveryPort inboxRecovery) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
        this.quarantineStore = quarantineStore;
        this.inboxRecovery = inboxRecovery;
    }

    /** Quarantined Chaincache lifecycle inbox rows for this chain — what {@link #resolveQuarantine}
     *  is about to clear, so an operator can review what wedged the stream before doing so. */
    public List<ChaincacheInboxRecoveryPort.QuarantinedInboxEvent> listQuarantinedInboxEvents(UUID chainConfigId) {
        return inboxRecovery.listQuarantinedInbox(chainConfigId);
    }

    @Transactional(readOnly = true)
    public List<ChainEffectView> listUnresolved() {
        return repository.findByStatusInOrderByRecordedAtDesc(UNRESOLVED_STATUSES).stream()
                .map(ChainEffectView::of).toList();
    }

    /** Re-runs the registered {@code ChainEffectCompensator} for one row — the manual "retry now"
     *  action for a {@code COMPENSATION_FAILED} row (the automatic retry job already does this on
     *  its own schedule; this is for an operator who doesn't want to wait, e.g. right after fixing
     *  whatever made the compensator fail). A no-op, safely, if the row isn't currently claimable
     *  (already resolved, or another dispatcher run is in flight) — see {@code
     *  CompensationDispatcher.compensate}'s javadoc. */
    @Transactional
    public CompensationOutcome retry(UUID chainEffectId) {
        if (!repository.existsById(chainEffectId)) {
            throw new EntityNotFoundException("ChainEffect", chainEffectId);
        }
        return dispatcher.compensate(chainEffectId);
    }

    /**
     * Records that an admin has reviewed an unresolved effect and accepts proceeding despite it —
     * the action that lifts {@code FinalityGateImpl}'s per-asset freeze. Does not change {@code
     * status}: the compensation genuinely failed or is irreversible, which stays true; only the
     * gate's willingness to keep blocking on it is lifted.
     *
     * @param reason mandatory, audited justification — same convention as {@code
     *               FinalityPolicyAdminService.createOverride}'s override reason
     * @throws IllegalArgumentException if the row is not currently in an unresolved status (there
     *         is nothing to acknowledge on a row that already compensated cleanly, or that was
     *         never a failure in the first place)
     */
    @Transactional
    public ChainEffectView acknowledge(UUID chainEffectId, String reason, UUID actorId, String actorRole) {
        Instant acknowledgedAt = Instant.now();
        if (repository.acknowledgeIfUnresolved(chainEffectId, reason, actorId, acknowledgedAt) == 0) {
            ChainEffect current = repository.findById(chainEffectId)
                    .orElseThrow(() -> new EntityNotFoundException("ChainEffect", chainEffectId));
            throw new IllegalArgumentException(
                    "ChainEffect " + chainEffectId + " is " + current.getStatus()
                            + (current.getAcknowledgedAt() == null ? "" : " and already acknowledged")
                            + ", not an unacknowledged unresolved compensation");
        }
        ChainEffect saved = repository.findById(chainEffectId)
                .orElseThrow(() -> new IllegalStateException(
                        "ChainEffect " + chainEffectId + " vanished after acknowledgement"));

        eventPublisher.publishEvent(new ChainEffectAcknowledgedEvent(
                saved.getId(), saved.getEffectType(), saved.getEntityType(), saved.getEntityId(),
                saved.getAssetId(), reason, actorId, actorRole, Instant.now()));
        return ChainEffectView.of(saved);
    }

    /**
     * Explicitly resolves a chain quarantine after every failed/irreversible effect on that chain
     * has either compensated successfully or been individually acknowledged. A retry never
     * auto-clears quarantine: the operator must review the complete incident and supply an audited
     * reason before durable ingestion and organization authorization resume.
     *
     * <p>Also the single recovery entry point when there is no {@code chain_quarantine} row at all
     * — a poison/malformed lifecycle envelope quarantines the Chaincache inbox and subscription
     * directly (see {@link ChaincacheInboxRecoveryPort}) without ever recording a reorg/finality
     * incident, since transport-layer corruption never reached a canonical-chain decision.
     */
    @Transactional
    public void resolveQuarantine(UUID chainConfigId, String reason, UUID actorId, String actorRole) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Quarantine resolution reason is required");
        }
        quarantineStore.lockChain(chainConfigId);
        var activeOpt = quarantineStore.findActive(chainConfigId);
        if (activeOpt.isEmpty()) {
            // A malformed/poison lifecycle envelope quarantines the Chaincache inbox and its
            // subscription directly (ChaincacheLifecycleFailureRecorder), with no chain_quarantine
            // row at all — that table models reorg/finality-violation incidents specifically, a
            // different failure class from a transport-level poison event that never reached a
            // canonical-chain decision. Recover that case here too, rather than forcing the
            // operator through a path that insists an incident exists which never did.
            int inboxCleared = inboxRecovery.clearQuarantinedInbox(chainConfigId);
            if (inboxCleared == 0) {
                throw new IllegalArgumentException("Chain " + chainConfigId + " has no active quarantine");
            }
            log.info("Cleared {} quarantined Chaincache lifecycle inbox row(s) for chain={} (no "
                    + "chain-level quarantine was active)", inboxCleared, chainConfigId);
            eventPublisher.publishEvent(new ChainQuarantineResolvedEvent(
                    chainConfigId, "none (inbox-only quarantine)", reason.strip(), actorId, actorRole,
                    Instant.now()));
            return;
        }
        var active = activeOpt.get();
        if (requiresCanonicalReconciliation(active.trigger())) {
            throw new IllegalStateException("Chain " + chainConfigId + " quarantine trigger "
                    + active.trigger() + " requires explicit canonical-state reconciliation; "
                    + "it cannot be cleared by the generic acknowledgement endpoint");
        }
        if (repository.existsByChainConfigIdAndStatusInAndAcknowledgedAtIsNull(
                chainConfigId, UNRESOLVED_STATUSES)) {
            throw new IllegalStateException("Chain " + chainConfigId
                    + " still has unacknowledged failed or irreversible compensations");
        }
        Instant resolvedAt = Instant.now();
        if (quarantineStore.resolve(chainConfigId, resolvedAt) != 1) {
            throw new IllegalStateException("Chain quarantine changed concurrently for " + chainConfigId);
        }
        // The chain_quarantine row and the Chaincache lifecycle inbox's own QUARANTINED rows are
        // two independent fail-closed states with a shared root cause — see
        // ChaincacheInboxRecoveryPort's javadoc for why resolving only one leaves either a
        // permanently wedged durable stream or gated operations resuming against a stream that
        // still can't advance. Cleared unconditionally: a chain can be quarantined without its
        // inbox ever having been (e.g. a local-finality-conflict trigger with no lifecycle event
        // involved), in which case this is a no-op.
        int inboxCleared = inboxRecovery.clearQuarantinedInbox(chainConfigId);
        if (inboxCleared > 0) {
            log.info("Cleared {} quarantined Chaincache lifecycle inbox row(s) for chain={} alongside "
                    + "quarantine resolution", inboxCleared, chainConfigId);
        }
        eventPublisher.publishEvent(new ChainQuarantineResolvedEvent(
                chainConfigId, active.reorgId(), reason.strip(), actorId, actorRole, resolvedAt));
    }

    private static boolean requiresCanonicalReconciliation(QuarantineTrigger trigger) {
        return trigger == QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION
                || trigger == QuarantineTrigger.UNRESOLVED_ANCESTRY
                || trigger == QuarantineTrigger.LOCAL_FINALITY_CONFLICT;
    }
}
