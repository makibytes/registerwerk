package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettledEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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
    private final ApplicationEventPublisher events;

    CorporateActionSettlementWriter(CorporateActionRepository corporateActionRepository,
                                     CorporateActionEntryRepository entryRepository,
                                     AssetCouponPaymentRepository couponPaymentRepository,
                                     ApplicationEventPublisher events) {
        this.corporateActionRepository = corporateActionRepository;
        this.entryRepository = entryRepository;
        this.couponPaymentRepository = couponPaymentRepository;
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
            ca.setSettlementTxHash(txHash);
            ca.setSettledAt(Instant.now());
            corporateActionRepository.save(ca);

            Instant settledAt = Instant.now();
            entryRepository.findByCorporateActionId(corporateActionId).forEach(entry -> {
                entry.setSettlementTxHash(txHash);
                entry.setSettledAt(settledAt);
                entryRepository.save(entry);
            });

            // AssetCouponPayment.couponStatus stayed SCHEDULED forever for coupons settled
            // through the CorporateAction pipeline — only the separate Erc3525AdminService path
            // ever wrote PAID. Close that gap here, at the single settlement chokepoint.
            if (ca.getCouponPaymentId() != null) {
                couponPaymentRepository.findById(ca.getCouponPaymentId()).ifPresent(payment -> {
                    payment.setCouponStatus(CouponStatus.PAID);
                    payment.setPaidDate(LocalDate.now());
                    payment.setTxRef(txHash);
                    couponPaymentRepository.save(payment);
                });
            }

            events.publishEvent(new CorporateActionSettledEvent(corporateActionId, actorId, actorRole, txHash));
        }, () -> log.warn("CorporateAction disappeared before settlement could be recorded: id={}", corporateActionId));
    }
}
