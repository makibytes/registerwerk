package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetCouponPayment;
import de.makibytes.registerwerk.asset.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.asset.api.CouponStatus;
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

    CouponPaymentJob(AssetCouponPaymentRepository couponPaymentRepository,
                     CorporateActionRepository corporateActionRepository,
                     CorporateActionService corporateActionService) {
        this.couponPaymentRepository = couponPaymentRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.corporateActionService = corporateActionService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        LocalDate today = LocalDate.now();
        log.info("CouponPaymentJob: scanning due coupon payments for date={}", today);

        List<AssetCouponPayment> due = couponPaymentRepository
                .findByCouponStatusAndScheduledDateLessThanEqual(CouponStatus.SCHEDULED, today);

        for (AssetCouponPayment payment : due) {
            try {
                CorporateAction action = new CorporateAction();
                action.setAssetId(payment.getAssetId());
                action.setActionType(CorporateAction.ActionType.COUPON);
                action.setAnnouncementDate(today);
                action.setPaymentDate(payment.getScheduledDate());
                action.setCouponPaymentId(payment.getId());
                action.setInitiatedBy(SYSTEM_ACTOR);
                action.setNotes("Auto-created from coupon_payment id=" + payment.getId());

                corporateActionService.announce(action);
                log.info("CouponPaymentJob: created CorporateAction for coupon payment id={}", payment.getId());
            } catch (Exception e) {
                log.error("CouponPaymentJob: failed to create action for payment id={}: {}", payment.getId(), e.getMessage());
            }
        }
        log.info("CouponPaymentJob: processed {} due coupon payments.", due.size());
    }
}
