package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionCancelledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionDualControlApprovedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
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
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates corporate-action lifecycle.
 * Settlement dispatch is token-standard-specific:
 * ERC-3525 → Erc3525AdminService, ERC-4626/7540 → vault NAV strike,
 * DAML bonds → CantonBondOperations.payCoupon, SPL-2022 → SolanaTokenService.
 */
@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final CorporateActionRepository repository;
    private final CorporateActionEntryRepository entryRepository;
    private final AssetHolderRepository holderRepository;
    private final CorporateActionSettlementWriter settlementWriter;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final ApplicationEventPublisher events;
    private final HolderBlockGate holderBlockGate;

    CorporateActionService(CorporateActionRepository repository,
                            CorporateActionEntryRepository entryRepository,
                            AssetHolderRepository holderRepository,
                            CorporateActionSettlementWriter settlementWriter,
                            AssetCouponPaymentRepository couponPaymentRepository,
                            ApplicationEventPublisher events,
                            HolderBlockGate holderBlockGate) {
        this.repository = repository;
        this.entryRepository = entryRepository;
        this.holderRepository = holderRepository;
        this.settlementWriter = settlementWriter;
        this.couponPaymentRepository = couponPaymentRepository;
        this.events = events;
        this.holderBlockGate = holderBlockGate;
    }

    public CorporateAction announce(CorporateAction action) {
        action.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(action);
        log.info("Corporate action announced: id={} type={} assetId={}", saved.getId(), saved.getActionType(), saved.getAssetId());
        events.publishEvent(new CorporateActionAnnouncedEvent(
                saved.getId(), null, "SYSTEM", saved.getAssetId(), saved.getActionType()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findByAsset(UUID assetId) {
        return repository.findByAssetId(assetId);
    }

    /**
     * Records dual-control approval for a corporate action's settlement (Vieraugenprinzip).
     * The approver must be a different actor than whoever initiated the action, mirroring
     * the same-user rejection already enforced in {@code ScreeningService.acceptHit}. The
     * REGISTRY_ADMIN role and freshness of {@code actorId} are already enforced by
     * {@code @RequiresStepUp(requireSecondApprover = true)} on the calling controller
     * endpoint before this method runs.
     */
    public CorporateAction approveForSettlement(UUID corporateActionId, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.SETTLED || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is already " + ca.getStatus());
        }
        if (actorId != null && actorId.equals(ca.getInitiatedBy())) {
            throw new IllegalArgumentException(
                    "Dual-control approver must be a different user than whoever initiated the corporate action.");
        }
        ca.setDualControlApproverId(actorId);
        ca.setDualControlApprovedAt(Instant.now());
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionDualControlApprovedEvent(
                corporateActionId, actorId, actorRole, ca.getInitiatedBy()));
        log.info("Corporate action dual-control approved: id={} approver={}", corporateActionId, actorId);
        return saved;
    }

    /**
     * Manually records settlement for a corporate action with no automated on-chain settlement
     * adapter — {@code CorporateActionSettlementListener} only dispatches Canton bond coupons
     * automatically; ERC-3525/ERC-4626/ERC-7540 and every other standard just log "requires
     * operator review" and were left stuck in AWAITING_SETTLEMENT forever, with no endpoint to
     * ever move them forward. The operator attests here that the payment was made through
     * whatever channel actually executed it (a manual on-chain tx, an off-chain bank transfer,
     * etc.) and supplies a reference for the audit trail. Gated by
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
     * Gated by {@code @RequiresStepUp(requireSecondApprover = true)} at the controller, same as
     * every other consequential corporate-action transition.
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
                if (ca.getDualControlApproverId() == null) {
                    log.warn("Corporate action {} is due for settlement (paymentDate={}) but has no dual-control "
                                    + "approval yet — skipping until approved via POST /corporate-actions/{}/approve-settlement.",
                            ca.getId(), ca.getPaymentDate(), ca.getId());
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

    private void settle(CorporateAction ca) {
        log.info("Settling corporate action: id={} type={} assetId={}", ca.getId(), ca.getActionType(), ca.getAssetId());
        requireNoBlockedEntitledHolders(ca);
        ca.setStatus(CorporateAction.Status.AWAITING_SETTLEMENT);
        repository.save(ca);
        // Dispatch is handled by CorporateActionSettlementListener in the blockchain module,
        // which looks up the asset's token standard and calls the appropriate chain service.
        events.publishEvent(new CorporateActionSettlementRequestedEvent(ca.getId(), ca.getAssetId(), ca.getActionType()));
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
