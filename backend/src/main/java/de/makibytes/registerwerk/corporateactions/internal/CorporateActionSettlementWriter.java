package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementBlockedEvent;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the outcome of an on-chain settlement dispatch — both the async Canton path
 * ({@link CorporateActionSettlementListener}) and the manual operator path
 * ({@code CorporateActionAdminController.markSettled}, for standards with no automated
 * settlement adapter, which today would otherwise sit in AWAITING_SETTLEMENT forever). Split
 * out from the listener because the async write happens on a
 * {@link java.util.concurrent.CompletableFuture} completion thread, well after the
 * listener's own transaction has closed — a separate bean is required so
 * {@code @Transactional} is applied through Spring's proxy rather than silently
 * skipped by same-class self-invocation.
 */
@Component
class CorporateActionSettlementWriter {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionSettlementWriter.class);

    private final CorporateActionRepository corporateActionRepository;
    private final CorporateActionEntryRepository entryRepository;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final RegisterClock registerClock;
    private final ApplicationEventPublisher events;

    /** Bond states a settled REDEMPTION clears (T3-05) — an OVERDUE or even DEFAULTED bond that is
     *  paid after all is REDEEMED, not left flagged. CALLED/REDEEMED are final and untouched. */
    private static final Set<BondStatus> REDEEMABLE =
            EnumSet.of(BondStatus.ACTIVE, BondStatus.MATURED, BondStatus.OVERDUE, BondStatus.DEFAULTED);

    CorporateActionSettlementWriter(CorporateActionRepository corporateActionRepository,
                                     CorporateActionEntryRepository entryRepository,
                                     AssetCouponPaymentRepository couponPaymentRepository,
                                     AssetBondTermsRepository bondTermsRepository,
                                     RegisterClock registerClock,
                                     ApplicationEventPublisher events) {
        this.corporateActionRepository = corporateActionRepository;
        this.entryRepository = entryRepository;
        this.couponPaymentRepository = couponPaymentRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.registerClock = registerClock;
        this.events = events;
    }

    /** Automated (Canton) settlement path — no operator actor, system-attributed. */
    @Transactional
    void markSettled(UUID corporateActionId, String txHash) {
        markSettled(corporateActionId, txHash, null, "SYSTEM");
    }

    /**
     * @param actorId   the operator confirming settlement (manual path), or null for the
     *                  automated Canton path
     * @param actorRole the operator's role, or {@code "SYSTEM"} for the automated path
     */
    @Transactional
    void markSettled(UUID corporateActionId, String txHash, UUID actorId, String actorRole) {
        corporateActionRepository.findById(corporateActionId).ifPresentOrElse(ca -> {
            ca.setStatus(CorporateAction.Status.SETTLED);
            ca.setSettlementHoldReason(null);
            ca.setSettlementTxHash(txHash);
            ca.setSettledAt(Instant.now());
            corporateActionRepository.save(ca);

            Instant settledAt = Instant.now();
            entryRepository.findByCorporateActionId(corporateActionId).forEach(entry -> {
                // T2-18 / H6: a held entry (nominee pool awaiting PARK-T2-18, or a holder that failed the eligibility
                // gate at payout time) must not be recorded as paid, nor count as realized income downstream.
                if (entry.getPayoutStatus() != CorporateActionEntry.PayoutStatus.PAYABLE) {
                    return;
                }
                entry.setSettlementTxHash(txHash);
                entry.setSettledAt(settledAt);
                entryRepository.save(entry);
            });

            // AssetCouponPayment.couponStatus stayed SCHEDULED forever for coupons settled
            // through the CorporateAction pipeline — only the separate Erc3525AdminService path
            // ever wrote PAID. Close that gap here, at the single settlement chokepoint. An
            // OVERDUE (or MISSED) coupon that is paid after all becomes PAID too (T3-05).
            if (ca.getCouponPaymentId() != null) {
                couponPaymentRepository.findById(ca.getCouponPaymentId()).ifPresent(payment -> {
                    payment.setCouponStatus(CouponStatus.PAID);
                    payment.setPaidDate(registerClock.today());
                    payment.setTxRef(txHash);
                    couponPaymentRepository.save(payment);
                });
            }

            updateBondStatus(ca);

            events.publishEvent(new CorporateActionSettledEvent(corporateActionId, actorId, actorRole, txHash));
        }, () -> log.warn("CorporateAction disappeared before settlement could be recorded: id={}", corporateActionId));
    }

    /**
     * T3-05: the settlement writer never touched the bond's status, so a paid redemption left the
     * bond DEFAULTED and a settled CALL left it ACTIVE — and the maturity job later raised a second
     * redemption for it. REDEMPTION → REDEEMED, CALL → CALLED. The asset status and the burn stay a
     * separate, 4-eyes operator step ({@code POST /assets/{id}/redeem}, which requires this action).
     */
    private void updateBondStatus(CorporateAction ca) {
        if (ca.getActionType() == null) {
            return;
        }
        BondStatus target = switch (ca.getActionType()) {
            case REDEMPTION -> BondStatus.REDEEMED;
            case CALL -> BondStatus.CALLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        bondTermsRepository.findById(ca.getAssetId()).ifPresent(terms -> {
            if (REDEEMABLE.contains(terms.getBondStatus())) {
                log.info("Bond assetId={} {} → {} ({} action {} settled)", ca.getAssetId(),
                        terms.getBondStatus(), target, ca.getActionType(), ca.getId());
                terms.setBondStatus(target);
                bondTermsRepository.save(terms);
            }
        });
    }

    /**
     * Records that automated settlement was deliberately not dispatched: appends {@code reason}
     * to the operator-visible notes (once — a redelivered settlement event does not duplicate it)
     * and audits it. The status stays {@code AWAITING_SETTLEMENT}.
     */
    @Transactional
    void recordSettlementBlocked(UUID corporateActionId, String reason) {
        corporateActionRepository.findById(corporateActionId).ifPresent(ca -> {
            String note = "settlement blocked: " + reason;
            if (ca.getNotes() != null && ca.getNotes().contains(note)) {
                return;
            }
            ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + note);
            corporateActionRepository.save(ca);
            events.publishEvent(new CorporateActionSettlementBlockedEvent(corporateActionId, reason));
        });
    }
}
