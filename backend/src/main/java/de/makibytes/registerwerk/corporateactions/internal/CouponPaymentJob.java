package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Quartz job that reads asset_coupon_payment rows with status=SCHEDULED and
 * scheduled_date <= today, creates CorporateAction(type=COUPON) rows and triggers settlement.
 */
@Component
public class CouponPaymentJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(CouponPaymentJob.class);

    /** Nil UUID used as the actor ID for system-initiated actions. */
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final CorporateActionService corporateActionService;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetRepository assetRepository;

    CouponPaymentJob(AssetCouponPaymentRepository couponPaymentRepository,
                     CorporateActionRepository corporateActionRepository,
                     CorporateActionService corporateActionService,
                     AssetBondTermsRepository bondTermsRepository,
                     AssetRepository assetRepository) {
        this.couponPaymentRepository = couponPaymentRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.corporateActionService = corporateActionService;
        this.bondTermsRepository = bondTermsRepository;
        this.assetRepository = assetRepository;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        LocalDate today = LocalDate.now();
        log.info("CouponPaymentJob: scanning due coupon payments for date={}", today);

        List<AssetCouponPayment> due = couponPaymentRepository
                .findByCouponStatusAndScheduledDateLessThanEqual(CouponStatus.SCHEDULED, today);

        for (AssetCouponPayment payment : due) {
            try {
                // Idempotency: settlement confirms asynchronously (and only some token
                // standards flip the coupon to PAID), so the SCHEDULED row may still be
                // visible on the next run. Without this guard every daily run would
                // create another CorporateAction and pay the coupon to all holders again.
                if (corporateActionRepository.existsByCouponPaymentId(payment.getId())) {
                    log.debug("CouponPaymentJob: action already exists for payment id={}, skipping.", payment.getId());
                    continue;
                }
                if (isTransferredOut(payment.getAssetId())) {
                    log.info("Coupon payment id={} due for assetId={} but its register was transferred to a "
                            + "successor operator — not auto-raising a coupon action here.",
                            payment.getId(), payment.getAssetId());
                    continue;
                }
                CorporateAction action = new CorporateAction();
                action.setAssetId(payment.getAssetId());
                action.setActionType(CorporateAction.ActionType.COUPON);
                action.setAnnouncementDate(today);
                // Record date defaults to the payment date itself for auto-created coupons —
                // there is no separate "ex-date" concept configured per bond today; this at
                // least lets the daily record-date job pick the action up and snapshot holder
                // entitlements instead of it sitting in ANNOUNCED forever.
                action.setRecordDate(payment.getScheduledDate());
                action.setPaymentDate(payment.getScheduledDate());
                action.setCouponPaymentId(payment.getId());
                action.setInitiatedBy(SYSTEM_ACTOR);
                action.setNotes("Auto-created from coupon_payment id=" + payment.getId());

                // AssetCouponPayment.amountPerUnit is known at coupon-schedule creation time;
                // copy it onto the CorporateAction so the Steuerbescheinigung/position-statement
                // income columns aren't null placeholders.
                action.setAmountPerUnit(payment.getAmountPerUnit());
                bondTermsRepository.findById(payment.getAssetId())
                        .map(AssetBondTerms::getCurrencyIso)
                        .ifPresent(action::setCurrency);

                corporateActionService.announce(action);
                log.info("CouponPaymentJob: created CorporateAction for coupon payment id={}", payment.getId());
            } catch (Exception e) {
                log.error("CouponPaymentJob: failed to create action for payment id={}: {}", payment.getId(), e.getMessage());
            }
        }
        log.info("CouponPaymentJob: processed {} due coupon payments.", due.size());
    }

    private boolean isTransferredOut(UUID assetId) {
        return assetRepository.findById(assetId)
                .map(asset -> asset.getStatus() == AssetStatus.TRANSFERRED_OUT)
                .orElse(false);
    }
}
