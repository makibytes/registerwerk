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
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
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
    private final AssetHolderRepository holderRepository;
    private final CorporateActionSettlementWriter settlementWriter;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CorporateActionProposalValidator proposalValidator;
    private final ApplicationEventPublisher events;
    private final HolderBlockGate holderBlockGate;
    private final FinalityGate finalityGate;

    CorporateActionService(CorporateActionRepository repository,
                            CorporateActionEntryRepository entryRepository,
                            AssetHolderRepository holderRepository,
                            CorporateActionSettlementWriter settlementWriter,
                            AssetCouponPaymentRepository couponPaymentRepository,
                            CorporateActionProposalValidator proposalValidator,
                            ApplicationEventPublisher events,
                            HolderBlockGate holderBlockGate,
                            FinalityGate finalityGate) {
        this.repository = repository;
        this.entryRepository = entryRepository;
        this.holderRepository = holderRepository;
        this.settlementWriter = settlementWriter;
        this.couponPaymentRepository = couponPaymentRepository;
        this.proposalValidator = proposalValidator;
        this.events = events;
        this.holderBlockGate = holderBlockGate;
        this.finalityGate = finalityGate;
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
     */
    public CorporateAction attestSettlementAsIssuer(UUID assetId, UUID corporateActionId, String attestationReference, UUID actorId, String actorRole) {
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
        if (ca.getIssuerAttestedAt() == null) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " has not been attested by its issuer yet — "
                            + "operator confirmation requires the issuer's attestation first (or an operator override).");
        }
        if (actorId != null && actorId.equals(ca.getIssuerAttestedBy())) {
            throw new IllegalArgumentException(
                    "Operator confirmer must be a different actor than whoever attested as issuer.");
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

    /** Daily job: transition ANNOUNCED → RECORD_DATE_SET → COMPUTED when dates are reached,
     *  dispatch settlement when due, and close out actions that finished settling. */
    @SchedulerLock(name = "corporateActionDailyTransitions", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 6 * * *")
    public void processDailyTransitions() {
        LocalDate today = LocalDate.now();

        List<CorporateAction> ready = repository.findReadyToCompute(today);
        for (CorporateAction ca : ready) {
            try {
                ca.setStatus(CorporateAction.Status.RECORD_DATE_SET);
                repository.save(ca);
                log.info("Corporate action record date set: id={}", ca.getId());
                snapshotEntriesAndCompute(ca);
            } catch (Exception e) {
                log.error("Failed to advance corporate action {}: {}", ca.getId(), e.getMessage());
            }
        }

        List<CorporateAction> due = repository.findDueForSettlement(today);
        for (CorporateAction ca : due) {
            try {
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
        markMissedCoupons(today);
    }

    /**
     * Marks {@code AssetCouponPayment.couponStatus = MISSED} for coupons whose CorporateAction
     * is overdue and still unsettled, distinguishing a coupon that failed to pay from one
     * merely awaiting settlement.
     */
    private void markMissedCoupons(LocalDate today) {
        for (CorporateAction overdue : repository.findOverdueCoupons(today)) {
            couponPaymentRepository.findById(overdue.getCouponPaymentId()).ifPresent(payment -> {
                if (payment.getCouponStatus() == CouponStatus.SCHEDULED) {
                    payment.setCouponStatus(CouponStatus.MISSED);
                    couponPaymentRepository.save(payment);
                    log.warn("Coupon missed: paymentId={} assetId={} scheduledDate={}",
                            payment.getId(), payment.getAssetId(), payment.getScheduledDate());
                }
            });
        }
    }

    /**
     * Snapshots each current holder's nominal amount as a {@code CorporateActionEntry} at record
     * date (entitlement is fixed then, not at settlement) and, when the action carries a known
     * {@code amountPerUnit} (coupons/interest payments), computes each entry's
     * {@code entitlementAmount} and the action's aggregate {@code totalAmount}, transitioning to
     * COMPUTED. Actions without a per-unit amount (splits, calls, etc.) still get COMPUTED — there
     * is simply nothing to compute — since RECORD_DATE_SET → COMPUTED is otherwise a dead-end
     * status no code ever advances past.
     */
    private void snapshotEntriesAndCompute(CorporateAction ca) {
        if (entryRepository.existsByCorporateActionId(ca.getId())) {
            return; // already snapshotted (defensive — processDailyTransitions runs at most daily)
        }
        // Entitlement snapshot must reflect the current register: a removed holder no longer
        // holds the position and must not receive a corporate-action entitlement for it.
        List<AssetHolder> holders = holderRepository.findActiveByAssetId(ca.getAssetId());
        BigDecimal amountPerUnit = ca.getAmountPerUnit();
        BigDecimal total = BigDecimal.ZERO;

        for (AssetHolder holder : holders) {
            CorporateActionEntry entry = new CorporateActionEntry();
            entry.setCorporateActionId(ca.getId());
            entry.setAssetHolderId(holder.getId());
            entry.setInvestorId(holder.getInvestorId());
            entry.setWalletAddress(holder.getWalletAddress());
            BigDecimal nominal = holder.getNominalAmount() != null ? holder.getNominalAmount() : BigDecimal.ZERO;
            entry.setNominalAtRecord(nominal);
            if (amountPerUnit != null) {
                BigDecimal entitlement = amountPerUnit.multiply(nominal);
                entry.setEntitlementAmount(entitlement);
                total = total.add(entitlement);
            }
            entryRepository.save(entry);
        }

        if (amountPerUnit != null) {
            ca.setTotalAmount(total);
        }
        ca.setStatus(CorporateAction.Status.COMPUTED);
        repository.save(ca);
        log.info("Corporate action computed: id={} holders={} totalAmount={}", ca.getId(), holders.size(), ca.getTotalAmount());
    }

    /**
     * Closes out SETTLED actions — the terminal transition to {@code CLOSED}. An action closes
     * as soon as it is observed SETTLED; no further reconciliation window is designed today.
     */
    private void closeSettledActions() {
        for (CorporateAction ca : repository.findByStatus(CorporateAction.Status.SETTLED)) {
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
