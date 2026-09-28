package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionCancelledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestationOverriddenEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionOperatorConfirmedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalApprovedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalRejectedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSnapshotBlockedEvent;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates corporate-action lifecycle.
 *
 * <p>Two families of creation: COUPON/REDEMPTION stay system-raised (see {@code CouponPaymentJob}/
 * {@code BondMaturityJob}), created directly via {@link #announce}. DIVIDEND/SPLIT/CALL are
 * issuer-proposed via {@link #propose} — a real human actor, reviewed by an operator
 * ({@link #approveProposal}/{@link #rejectProposal}) before joining the same ANNOUNCED pipeline.
 *
 * <p>Settlement approval is a two-party control: the issuer attests the underlying obligation is
 * ready ({@link #attestSettlementAsIssuer}, or an audited operator {@link #overrideIssuerAttestation}
 * for an issuer who never logs in), then an operator confirms register/on-chain execution
 * readiness ({@link #confirmSettlementAsOperator}) — refused until the issuer half is set.
 *
 * <p>Settlement dispatch is token-standard-specific:
 * ERC-3525 → Erc3525AdminService, ERC-4626/7540 → vault NAV strike,
 * DAML bonds → CantonBondOperations.payCoupon/redeem/earlyCall, SPL-2022 → SolanaTokenService.
 */
@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    /** Nil UUID used as the actor ID for system-initiated actions — mirrors
     *  {@code CouponPaymentJob}/{@code BondMaturityJob}'s own constant. */
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    /** What a holder (investor) may see — an issuer's unreviewed or rejected proposal is a draft,
     *  not a register fact, so it's excluded from {@link #findByAssetForHolder}. */
    private static final Set<CorporateAction.Status> HOLDER_VISIBLE_STATUSES =
            EnumSet.complementOf(EnumSet.of(CorporateAction.Status.PROPOSED, CorporateAction.Status.REJECTED));

    private final CorporateActionRepository repository;
    private final CorporateActionEntryRepository entryRepository;
    private final RecordDatePositionResolver positionResolver;
    private final CorporateActionSettlementWriter settlementWriter;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CorporateActionProposalValidator proposalValidator;
    private final ApplicationEventPublisher events;
    private final HolderBlockGate holderBlockGate;
    private final FinalityGate finalityGate;
    private final RegisterFreshnessGate registerFreshnessGate;
    private final AssetBondTermsRepository bondTermsRepository;
    private final RegisterClock registerClock;

    CorporateActionService(CorporateActionRepository repository,
                            CorporateActionEntryRepository entryRepository,
                            RecordDatePositionResolver positionResolver,
                            CorporateActionSettlementWriter settlementWriter,
                            AssetCouponPaymentRepository couponPaymentRepository,
                            CorporateActionProposalValidator proposalValidator,
                            ApplicationEventPublisher events,
                            HolderBlockGate holderBlockGate,
                            FinalityGate finalityGate,
                            RegisterFreshnessGate registerFreshnessGate,
                            AssetBondTermsRepository bondTermsRepository,
                            RegisterClock registerClock) {
        this.repository = repository;
        this.entryRepository = entryRepository;
        this.positionResolver = positionResolver;
        this.settlementWriter = settlementWriter;
        this.couponPaymentRepository = couponPaymentRepository;
        this.proposalValidator = proposalValidator;
        this.events = events;
        this.holderBlockGate = holderBlockGate;
        this.finalityGate = finalityGate;
        this.registerFreshnessGate = registerFreshnessGate;
        this.bondTermsRepository = bondTermsRepository;
        this.registerClock = registerClock;
    }

    /** System-raised creation path — {@code CouponPaymentJob}/{@code BondMaturityJob} only. */
    public CorporateAction announce(CorporateAction action) {
        action.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(action);
        log.info("Corporate action announced: id={} type={} assetId={}", saved.getId(), saved.getActionType(), saved.getAssetId());
        events.publishEvent(new CorporateActionAnnouncedEvent(
                saved.getId(), null, "SYSTEM", saved.getAssetId(), saved.getActionType()));
        return saved;
    }

    /**
     * Issuer-initiated creation path for DIVIDEND/SPLIT/CALL — validated by
     * {@link CorporateActionProposalValidator}, starts life {@code PROPOSED}, invisible to
     * {@code findReadyToCompute}/{@code findDueForSettlement} until an operator approves it.
     * {@code actorId} is the real proposing issuer — the first non-system {@code initiatedBy}
     * this system has ever recorded.
     */
    public CorporateAction propose(UUID assetId, ProposeCorporateActionRequest request, UUID actorId, String actorRole) {
        registerFreshnessGate.requireRegisterOpen(assetId, "Corporate action proposal");
        CorporateAction action = proposalValidator.validateAndBuild(assetId, request);
        action.setInitiatedBy(actorId);
        action.setStatus(CorporateAction.Status.PROPOSED);
        CorporateAction saved = repository.save(action);

        events.publishEvent(new CorporateActionProposedEvent(
                saved.getId(), actorId, actorRole, saved.getAssetId(), saved.getActionType()));
        log.info("Corporate action proposed: id={} type={} assetId={} proposedBy={}",
                saved.getId(), saved.getActionType(), saved.getAssetId(), actorId);
        return saved;
    }

    /** Operator approves an issuer's proposal — {@code PROPOSED} → {@code ANNOUNCED}, joining the
     *  existing pipeline unchanged. Fires both the proposal-review event and the pre-existing
     *  {@link CorporateActionAnnouncedEvent} so "every announced action" audit consumers see it. */
    public CorporateAction approveProposal(UUID corporateActionId, UUID actorId, String actorRole) {
        CorporateAction ca = requireProposed(corporateActionId);
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Corporate action approval");
        // T3-12: two-party control — an admin who proposed while impersonating the issuer (the
        // token's sub stays the admin's own id) must not approve that proposal themselves.
        if (actorId != null && actorId.equals(ca.getInitiatedBy())) {
            throw new AccessDeniedException("Corporate action " + corporateActionId
                    + " was proposed by the same user — a different operator must approve it.");
        }
        // T3-06: the record date must still be ahead at approval; the snapshot is fixed as of its end.
        String recordDateRefusal = CorporateActionProposalValidator.recordDateRefusal(ca.getRecordDate(), registerClock.today());
        if (recordDateRefusal != null) {
            throw new IllegalStateException(recordDateRefusal + " Reject this proposal so the issuer can re-propose it.");
        }
        ca.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionProposalApprovedEvent(corporateActionId, actorId, actorRole, ca.getInitiatedBy()));
        events.publishEvent(new CorporateActionAnnouncedEvent(
                saved.getId(), actorId, actorRole, saved.getAssetId(), saved.getActionType()));
        log.info("Corporate action proposal approved: id={} approver={}", corporateActionId, actorId);
        return saved;
    }

    /** Operator rejects an issuer's proposal — terminal, distinct from {@code cancel} (which
     *  unwinds an already-live announcement). */
    public CorporateAction rejectProposal(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = requireProposed(corporateActionId);
        ca.setStatus(CorporateAction.Status.REJECTED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "rejected: " + reason);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionProposalRejectedEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action proposal rejected: id={} reason={}", corporateActionId, reason);
        return saved;
    }

    /** Issuer withdraws their own still-unreviewed proposal. {@code assetId} must match the
     *  action's own — the controller's {@code @PreAuthorize} only checks that the caller owns
     *  {@code assetId} from the path, not that {@code corporateActionId} actually belongs to it. */
    public CorporateAction withdrawProposal(UUID assetId, UUID corporateActionId, UUID actorId) {
        CorporateAction ca = requireProposed(corporateActionId);
        requireBelongsToAsset(ca, assetId);
        ca.setStatus(CorporateAction.Status.CANCELLED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "withdrawn by proposer");
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionCancelledEvent(corporateActionId, actorId, "ISSUER", "withdrawn by proposer"));
        log.info("Corporate action proposal withdrawn: id={} by={}", corporateActionId, actorId);
        return saved;
    }

    /** Guards against an issuer authorized for {@code assetId} (via
     *  {@code @assetAccessChecker.canActAsIssuer(#assetId, ...)} at the controller) supplying a
     *  {@code corporateActionId} that actually belongs to a <em>different</em> asset — the
     *  {@code @PreAuthorize} SpEL only ever checks the path's own {@code assetId}. */
    private void requireBelongsToAsset(CorporateAction ca, UUID assetId) {
        if (!ca.getAssetId().equals(assetId)) {
            throw new EntityNotFoundException("CorporateAction", ca.getId());
        }
    }

    private CorporateAction requireProposed(UUID corporateActionId) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() != CorporateAction.Status.PROPOSED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is not PROPOSED (status=" + ca.getStatus() + ")");
        }
        return ca;
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findByAsset(UUID assetId) {
        return repository.findByAssetId(assetId);
    }

    /** Investor-facing list for {@code MeCorporateActionController} — excludes an issuer's own
     *  {@code PROPOSED}/{@code REJECTED} drafts via {@link CorporateActionRepository#findByAssetIdAndStatusIn},
     *  the dedicated query this method exists to actually use (see that repository method's javadoc). */
    @Transactional(readOnly = true)
    public List<CorporateAction> findByAssetForHolder(UUID assetId) {
        return repository.findByAssetIdAndStatusIn(assetId, HOLDER_VISIBLE_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findProposalsPendingReview() {
        return repository.findByStatusOrderByCreatedAtAsc(CorporateAction.Status.PROPOSED);
    }

    /**
     * Issuer attestation — the first of the two required parties before settlement can be
     * confirmed. Refuses on a terminal or unreviewed status; no step-up (see this class's
     * javadoc for why — {@code frontend-customer} has no step-up UI today).
     *
     * <p>T3-12: refused (403) when {@code callerIsOperator} — a REGISTRY_ADMIN, or an admin
     * impersonating the issuer. Operators attest only through the audited, step-up-gated
     * {@link #overrideIssuerAttestation}; otherwise one operator could supply both halves.
     */
    public CorporateAction attestSettlementAsIssuer(UUID assetId, UUID corporateActionId, String attestationReference,
                                                    UUID actorId, String actorRole, boolean callerIsOperator) {
        if (callerIsOperator) {
            throw new AccessDeniedException("Operators cannot attest as the issuer — use the operator "
                    + "override-attestation (step-up, reason, separately audited) instead.");
        }
        CorporateAction ca = requireAttestable(corporateActionId);
        requireBelongsToAsset(ca, assetId);
        ca.setIssuerAttestedBy(actorId);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestationRef(attestationReference);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionIssuerAttestedEvent(corporateActionId, actorId, actorRole, attestationReference));
        log.info("Corporate action issuer-attested: id={} attester={}", corporateActionId, actorId);
        return saved;
    }

    /**
     * Operator override of the issuer-attestation requirement — the escape hatch for an issuer
     * who never logs in to attest. Always a distinct, separately-audited event from a genuine
     * attestation (see {@link CorporateActionIssuerAttestationOverriddenEvent}'s javadoc).
     */
    public CorporateAction overrideIssuerAttestation(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = requireAttestable(corporateActionId);
        ca.setIssuerAttestedBy(actorId);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestationRef("OPERATOR_OVERRIDE: " + reason);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionIssuerAttestationOverriddenEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action issuer-attestation overridden by operator: id={} operator={} reason={}",
                corporateActionId, actorId, reason);
        return saved;
    }

    private CorporateAction requireAttestable(UUID corporateActionId) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.SETTLED || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED || ca.getStatus() == CorporateAction.Status.PROPOSED
                || ca.getStatus() == CorporateAction.Status.REJECTED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is not attestable (status=" + ca.getStatus() + ")");
        }
        return ca;
    }

    /**
     * Operator confirmation — the second of the two required parties. Refused while the issuer
     * half is missing (issuer-first ordering, see this class's javadoc), and while the confirmer
     * is the same actor as the attester. Gated on {@link GatedOperation#CORPORATE_ACTION_SETTLEMENT_CONFIRM}
     * immediately before the write — {@code currentLevel} is passed as {@code FINALIZED}
     * unconditionally, the same reasoning {@code RegisterTransferService} uses: entitlements are
     * computed from {@code AssetHolder.nominalAmount}, which {@code HolderDataService} only ever
     * populates from FINALIZED transfers.
     */
    public CorporateAction confirmSettlementAsOperator(UUID corporateActionId, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.SETTLED || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is already " + ca.getStatus());
        }
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Corporate action settlement confirmation");
        if (ca.getIssuerAttestedAt() == null) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " has not been attested by its issuer yet — "
                            + "operator confirmation requires the issuer's attestation first (or an operator override).");
        }
        if (actorId != null && actorId.equals(ca.getIssuerAttestedBy())) {
            throw new IllegalArgumentException(
                    "Operator confirmer must be a different actor than whoever attested as issuer.");
        }
        if (ca.getStatus() == CorporateAction.Status.SNAPSHOT_BLOCKED) {
            throw new IllegalStateException("Corporate action " + corporateActionId
                    + " has no entitlement snapshot yet (SNAPSHOT_BLOCKED): " + ca.getSnapshotBlockedReason());
        }
        // T2-18: no settlement approval on a register that is not reconciled with the chain. A
        // record date still in the future is enforced by the snapshot itself, so only BLOCKED counts then.
        LocalDate recordDate = ca.getRecordDate() != null && ca.getRecordDate().isBefore(registerClock.today())
                ? ca.getRecordDate() : null;
        Optional<String> registerBlocked = registerFreshnessGate.blockedReason(ca.getAssetId(), recordDate);
        if (registerBlocked.isPresent()) {
            throw new IllegalStateException("Settlement confirmation refused for corporate action "
                    + corporateActionId + ": " + registerBlocked.get());
        }
        finalityGate.require(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, ca.getAssetId(),
                resolveTokenStandard(corporateActionId), FinalityLevel.FINALIZED);

        ca.setDualControlApproverId(actorId);
        ca.setDualControlApprovedAt(Instant.now());
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionOperatorConfirmedEvent(
                corporateActionId, actorId, actorRole, ca.getIssuerAttestedBy()));
        log.info("Corporate action operator-confirmed: id={} confirmer={}", corporateActionId, actorId);
        return saved;
    }

    /**
     * Manually records settlement for a corporate action with no automated on-chain settlement
     * adapter — {@code CorporateActionSettlementListener} only dispatches Canton bond coupons/
     * redemptions/early-calls automatically; SPLIT (no on-chain split primitive exists on any
     * supported token standard), ERC-3525/ERC-4626/ERC-7540, and every other standard just log
     * "requires operator review" and have no other path out of AWAITING_SETTLEMENT. The operator
     * attests here that the payment/action was executed through whatever channel actually did it
     * and supplies a reference for the audit trail. Gated by
     * {@code @RequiresStepUp(requireSecondApprover = true)} at the controller — this is exactly
     * as consequential as the automated path from the register's point of view.
     */
    public CorporateAction markSettledManually(UUID corporateActionId, String reference, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() != CorporateAction.Status.AWAITING_SETTLEMENT) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " is not AWAITING_SETTLEMENT (status=" + ca.getStatus() + ")");
        }
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Manual corporate action settlement");
        requireNoBlockedEntitledHolders(ca);
        finalityGate.require(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, ca.getAssetId(),
                resolveTokenStandard(corporateActionId), FinalityLevel.FINALIZED);
        settlementWriter.markSettled(corporateActionId, reference, actorId, actorRole);
        return repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
    }

    /**
     * Cancels a corporate action — {@code Status.CANCELLED} has always
     * existed and every settlement/idempotency query already excludes it, but until now nothing
     * ever set it: a mistakenly-raised or no-longer-applicable action (e.g. a coupon
     * auto-created against the wrong asset, or one superseded by a corrected re-announcement)
     * had no way out of the pipeline. Refused once the action has reached AWAITING_SETTLEMENT
     * or beyond — at that point money/tokens may already be in flight or settled, and reversing
     * it is an operational/legal matter outside this method's scope, not a simple status flip.
     * Operator-only (unlike proposal withdrawal, unwinding a live register-affecting
     * announcement is a registrar act) — gated by
     * {@code @RequiresStepUp(requireSecondApprover = true)} at the controller.
     */
    public CorporateAction cancel(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.AWAITING_SETTLEMENT
                || ca.getStatus() == CorporateAction.Status.SETTLED
                || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " cannot be cancelled from status " + ca.getStatus());
        }
        ca.setStatus(CorporateAction.Status.CANCELLED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "cancelled: " + reason);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionCancelledEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action cancelled: id={} reason={}", corporateActionId, reason);
        return saved;
    }

    /** Daily job: transition ANNOUNCED → RECORD_DATE_SET → COMPUTED once the record date is over,
     *  dispatch settlement when due, close out actions that finished settling, and flag overdue
     *  coupons. 06:00 register time — after CouponPaymentJob (05:30) and BondMaturityJob (05:45),
     *  so actions they raise the same morning are processed in this run (T3-05). */
    @SchedulerLock(name = "corporateActionDailyTransitions", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 6 * * *", zone = "${registerwerk.register.time-zone:Europe/Berlin}")
    public void processDailyTransitions() {
        LocalDate today = registerClock.today();

        List<CorporateAction> ready = repository.findReadyToCompute(today);
        for (CorporateAction ca : ready) {
            try {
                if (registerFreshnessGate.isRegisterFrozen(ca.getAssetId())) {
                    log.warn("Corporate action {} not advanced: register of asset {} is frozen/transferred (T3-07)",
                            ca.getId(), ca.getAssetId());
                    continue;
                }
                // T2-18 (SRE veto: no silent refusal): a register that is BLOCKED or not reconciled
                // since the record date must not be snapshotted; the action is parked visibly as
                // SNAPSHOT_BLOCKED (audited, metric + alert) and retried on the next run.
                Optional<String> registerBlocked = registerFreshnessGate.blockedReason(ca.getAssetId(), ca.getRecordDate());
                if (registerBlocked.isPresent()) {
                    markSnapshotBlocked(ca, registerBlocked.get());
                    continue;
                }
                snapshotEntriesAndCompute(ca);
            } catch (Exception e) {
                log.error("Failed to advance corporate action {}: {}", ca.getId(), e.getMessage());
            }
        }

        List<CorporateAction> due = repository.findDueForSettlement(today);
        for (CorporateAction ca : due) {
            try {
                if (registerFreshnessGate.isRegisterFrozen(ca.getAssetId())) {
                    log.warn("Corporate action {} settlement skipped: register of asset {} is frozen/transferred (T3-07)",
                            ca.getId(), ca.getAssetId());
                    continue;
                }
                if (ca.getIssuerAttestedAt() == null || ca.getDualControlApproverId() == null) {
                    log.warn("Corporate action {} is due for settlement (paymentDate={}) but is missing {} — "
                                    + "skipping until both parties have signed off.",
                            ca.getId(), ca.getPaymentDate(),
                            ca.getIssuerAttestedAt() == null && ca.getDualControlApproverId() == null
                                    ? "both the issuer attestation and the operator confirmation"
                                    : ca.getIssuerAttestedAt() == null ? "the issuer attestation" : "the operator confirmation");
                    continue;
                }
                settle(ca);
            } catch (Exception e) {
                log.error("Settlement failed for corporate action {}: {}", ca.getId(), e.getMessage());
            }
        }

        closeSettledActions();
        markOverdueAndMissedCoupons(today);
    }

    private void markSnapshotBlocked(CorporateAction ca, String reason) {
        boolean changed = ca.getStatus() != CorporateAction.Status.SNAPSHOT_BLOCKED
                || !Objects.equals(reason, ca.getSnapshotBlockedReason());
        ca.setStatus(CorporateAction.Status.SNAPSHOT_BLOCKED);
        ca.setSnapshotBlockedReason(reason);
        repository.save(ca);
        if (changed) {
            events.publishEvent(new CorporateActionSnapshotBlockedEvent(ca.getId(), ca.getAssetId(), reason));
        }
        log.error("Corporate action {} snapshot BLOCKED (recordDate={}): {}", ca.getId(), ca.getRecordDate(), reason);
    }

    /**
     * T3-05: a coupon whose action is unsettled after its payment date is {@code OVERDUE}
     * (operator-visible; customers see "payment pending"); only once the bond's interest grace
     * period ({@code interestGraceDays}, default 30) has also passed is it {@code MISSED}. Before,
     * it was MISSED the morning after the payment date — publicly, and for every coupon.
     */
    private void markOverdueAndMissedCoupons(LocalDate today) {
        for (CorporateAction overdue : repository.findOverdueCoupons(today)) {
            couponPaymentRepository.findById(overdue.getCouponPaymentId()).ifPresent(payment -> {
                if (payment.getCouponStatus() != CouponStatus.SCHEDULED && payment.getCouponStatus() != CouponStatus.OVERDUE) {
                    return;
                }
                int grace = bondTermsRepository.findById(payment.getAssetId())
                        .map(AssetBondTerms::getInterestGraceDays).orElse(0);
                if (today.isAfter(overdue.getPaymentDate().plusDays(grace))) {
                    payment.setCouponStatus(CouponStatus.MISSED);
                    couponPaymentRepository.save(payment);
                    log.warn("Coupon missed: paymentId={} assetId={} paymentDate={} — unsettled after the {}-day grace period",
                            payment.getId(), payment.getAssetId(), overdue.getPaymentDate(), grace);
                } else if (payment.getCouponStatus() == CouponStatus.SCHEDULED) {
                    payment.setCouponStatus(CouponStatus.OVERDUE);
                    couponPaymentRepository.save(payment);
                    log.warn("Coupon overdue: paymentId={} assetId={} paymentDate={} (grace until {})",
                            payment.getId(), payment.getAssetId(), overdue.getPaymentDate(),
                            overdue.getPaymentDate().plusDays(grace));
                }
            });
        }
    }

    /**
     * Snapshots each holder's position <em>as of the end of the record date</em> (T3-06, see
     * {@link RecordDatePositionResolver}) as a {@code CorporateActionEntry} and, when the action
     * carries a known {@code amountPerUnit} (coupons/interest payments), computes each entry's
     * {@code entitlementAmount} and the action's aggregate {@code totalAmount}, transitioning to
     * COMPUTED. Actions without a per-unit amount (splits, calls, etc.) still get COMPUTED — there
     * is simply nothing to compute — since RECORD_DATE_SET → COMPUTED is otherwise a dead-end
     * status no code ever advances past. When the as-of positions cannot be established yet (chain
     * transfers before the cut-off not final, a wallet unmapped at the record date) the action is
     * SNAPSHOT_BLOCKED and retried, still against the same record date.
     *
     * <p>Rounding (T3-05): each entitlement is {@code amountPerUnit × nominal} rounded HALF_EVEN to
     * the currency's minor unit; {@code totalAmount} is the sum of the rounded payable entitlements
     * and {@code roundingResidual} = Σ unrounded − Σ rounded is kept for the operator confirmation.
     *
     * <p>A nominee-pool holder's entry (T2-18) is snapshotted with its entitlement but marked
     * {@code HELD_LOOK_THROUGH} and left out of {@code totalAmount}: who is entitled to payments on
     * pledged/escrowed units is undecided (PARK-T2-18), so they are neither paid to the pool nor
     * silently dropped.
     */
    private void snapshotEntriesAndCompute(CorporateAction ca) {
        if (entryRepository.existsByCorporateActionId(ca.getId())) {
            return; // already snapshotted (defensive — processDailyTransitions runs at most daily)
        }
        RecordDatePositionResolver.Resolution resolution =
                positionResolver.resolve(ca.getAssetId(), registerClock.endOfDay(ca.getRecordDate()));
        if (resolution.blockedReason().isPresent()) {
            markSnapshotBlocked(ca, resolution.blockedReason().get());
            return;
        }
        ca.setSnapshotBlockedReason(null);
        ca.setStatus(CorporateAction.Status.RECORD_DATE_SET);
        for (String note : resolution.notes()) {
            ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + note);
        }
        repository.save(ca);
        log.info("Corporate action record date set: id={} recordDate={}", ca.getId(), ca.getRecordDate());

        BigDecimal amountPerUnit = ca.getAmountPerUnit();
        int minorUnits = minorUnits(ca.getCurrency());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal unroundedTotal = BigDecimal.ZERO;

        for (RecordDatePositionResolver.Position position : resolution.positions()) {
            CorporateActionEntry entry = new CorporateActionEntry();
            entry.setCorporateActionId(ca.getId());
            entry.setAssetHolderId(position.assetHolderId());
            entry.setInvestorId(position.investorId());
            entry.setWalletAddress(position.walletAddress());
            BigDecimal nominal = position.nominal();
            entry.setNominalAtRecord(nominal);
            boolean held = position.holderKind() == HolderKind.NOMINEE_POOL;
            entry.setPayoutStatus(held ? CorporateActionEntry.PayoutStatus.HELD_LOOK_THROUGH
                    : CorporateActionEntry.PayoutStatus.PAYABLE);
            if (amountPerUnit != null) {
                BigDecimal unrounded = amountPerUnit.multiply(nominal);
                BigDecimal entitlement = unrounded.setScale(minorUnits, RoundingMode.HALF_EVEN);
                entry.setEntitlementAmount(entitlement);
                if (!held) {
                    total = total.add(entitlement);
                    unroundedTotal = unroundedTotal.add(unrounded);
                }
            }
            entryRepository.save(entry);
        }

        if (amountPerUnit != null) {
            ca.setTotalAmount(total);
            ca.setRoundingResidual(unroundedTotal.subtract(total));
        }
        ca.setStatus(CorporateAction.Status.COMPUTED);
        repository.save(ca);
        log.info("Corporate action computed: id={} entries={} totalAmount={} roundingResidual={}", ca.getId(),
                resolution.positions().size(), ca.getTotalAmount(), ca.getRoundingResidual());
    }

    /** ISO 4217 minor units of {@code currency}; 2 when unknown or not a fiat code. */
    static int minorUnits(String currency) {
        if (currency == null || currency.isBlank()) {
            return 2;
        }
        try {
            int digits = Currency.getInstance(currency.trim().toUpperCase()).getDefaultFractionDigits();
            return digits >= 0 ? digits : 2;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /**
     * Closes out SETTLED actions — the terminal transition to {@code CLOSED}. An action closes
     * as soon as it is observed SETTLED; no further reconciliation window is designed today.
     *
     * <p>T3-02: except while a nominee-pool (HELD_LOOK_THROUGH) entry still carries a non-zero
     * entitlement — settlement skipped it and nothing resolves it yet (PARK-T2-18), so closing
     * would bury it. Such an action stays SETTLED, flagged {@code heldOutstanding} (counted by
     * {@code registerwerk_corporate_action_held_outstanding}, shown as a chip to operators).
     */
    private void closeSettledActions() {
        for (CorporateAction ca : repository.findByStatus(CorporateAction.Status.SETTLED)) {
            if (entryRepository.existsHeldWithEntitlement(ca.getId())) {
                if (!ca.isHeldOutstanding()) {
                    ca.setHeldOutstanding(true);
                    ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "")
                            + "held look-through entitlements outstanding (PARK-T2-18)");
                    repository.save(ca);
                    log.warn("Corporate action {} not closed: nominee-pool entitlements are held and unresolved "
                            + "(PARK-T2-18)", ca.getId());
                }
                continue;
            }
            ca.setStatus(CorporateAction.Status.CLOSED);
            repository.save(ca);
            log.info("Corporate action closed: id={}", ca.getId());
        }
    }

    /**
     * @see FinalityGate — the cron path must {@code check}-and-skip, never {@code require} (an
     *      uncaught {@code FinalityNotReachedException} would fail the whole daily run, not just
     *      this one row). A blocked action simply stays COMPUTED and is re-evaluated on the next
     *      run, same as the existing Sperrvermerk hold below.
     */
    private void settle(CorporateAction ca) {
        FinalityDecision decision = finalityGate.check(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM,
                ca.getAssetId(), resolveTokenStandard(ca.getId()), FinalityLevel.FINALIZED);
        if (decision instanceof FinalityDecision.Blocked blocked) {
            log.warn("Corporate action {} settlement held: {} (reason={})", ca.getId(), blocked.explanation(), blocked.reason());
            return;
        }
        log.info("Settling corporate action: id={} type={} assetId={}", ca.getId(), ca.getActionType(), ca.getAssetId());
        requireNoBlockedEntitledHolders(ca);
        ca.setStatus(CorporateAction.Status.AWAITING_SETTLEMENT);
        repository.save(ca);
        // Dispatch is handled by CorporateActionSettlementListener in the blockchain module,
        // which looks up the asset's token standard and calls the appropriate chain service.
        events.publishEvent(new CorporateActionSettlementRequestedEvent(ca.getId(), ca.getAssetId(), ca.getActionType()));
    }

    private TokenStandard resolveTokenStandard(UUID corporateActionId) {
        String standard = repository.findTokenStandardByCorpAction(corporateActionId);
        return standard != null ? TokenStandard.valueOf(standard) : null;
    }

    /**
     * Fail-closed §16 eWpG Sperrvermerk check across every entitled holder before a corporate
     * action's payment is dispatched, via {@link HolderBlockGate} — a legally blocked holder
     * must not receive its computed entitlement's payout. Checked here (settlement-dispatch
     * time), not in {@link #snapshotEntriesAndCompute} — a Sperrvermerk restricts
     * disposal/payment, not the register's accurate record of legal entitlement at record date,
     * so entitlement computation itself is unaffected.
     *
     * <p>Canton's {@code payCoupon}/{@code redeem} is a single aggregate on-ledger call across
     * all holders of a bond, not a per-holder transfer — it cannot surgically exclude one
     * blocked holder's share. If ANY entitled holder is blocked, the ENTIRE settlement is held
     * for operator review rather than partially dispatched; the action simply stays COMPUTED
     * and is re-evaluated by the next daily run once the block is lifted (or an operator
     * otherwise resolves it) — no separate "blocked" status is needed for this.
     */
    private void requireNoBlockedEntitledHolders(CorporateAction ca) {
        for (CorporateActionEntry entry : entryRepository.findByCorporateActionId(ca.getId())) {
            if (holderBlockGate.isBlocked(entry.getInvestorId(), entry.getWalletAddress())) {
                throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                        "Corporate action " + ca.getId() + " has an entitled holder (investor="
                        + entry.getInvestorId() + ") subject to an active §16 eWpG Sperrvermerk "
                        + "(legal block) — settlement refused until resolved.");
            }
        }
    }
}
