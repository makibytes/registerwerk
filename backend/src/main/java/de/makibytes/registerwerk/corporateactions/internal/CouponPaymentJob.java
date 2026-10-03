package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.schedule.BusinessDayCalendar;
import de.makibytes.registerwerk.deployment.api.schedule.HolidayCalendar;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.shared.RegisterClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raises CorporateAction(type=COUPON) rows from SCHEDULED asset_coupon_payment rows once their
 * <em>announcement date</em> is reached (T3-05) — record date and payment date come from the
 * schedule row (record = payment − offset business days, announcement = record − lead). Raising on
 * the payment date itself, as before, left no time to snapshot, attest and confirm, so every
 * coupon went overdue. Legacy rows without these dates get them from the bond's conventions.
 *
 * <p>Runs as a ShedLock-protected scheduled job so only one backend instance processes a due
 * coupon cycle. H8: the job holds no transaction of its own - every coupon payment is handled in its own
 * {@code REQUIRES_NEW} transaction ({@link IsolatedTransactionExecutor}), so one failing payment cannot roll back the
 * actions already raised for the others.
 */
@Component
public class CouponPaymentJob {

    private static final Logger log = LoggerFactory.getLogger(CouponPaymentJob.class);

    /** Nil UUID used as the actor ID for system-initiated actions. */
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final CorporateActionService corporateActionService;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetRepository assetRepository;
    private final RegisterClock registerClock;
    private final IsolatedTransactionExecutor isolated;

    /** Legacy rows (no announcement date) are considered this far ahead of their payment date. */
    private static final int LEGACY_HORIZON_DAYS = 120;

    CouponPaymentJob(AssetCouponPaymentRepository couponPaymentRepository,
                     CorporateActionRepository corporateActionRepository,
                     CorporateActionService corporateActionService,
                     AssetBondTermsRepository bondTermsRepository,
                     AssetRepository assetRepository,
                     RegisterClock registerClock,
                     IsolatedTransactionExecutor isolated) {
        this.couponPaymentRepository = couponPaymentRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.corporateActionService = corporateActionService;
        this.bondTermsRepository = bondTermsRepository;
        this.assetRepository = assetRepository;
        this.registerClock = registerClock;
        this.isolated = isolated;
    }

    /** Daily at 05:30 register time — strictly before BondMaturityJob (05:45) and the daily
     *  corporate-action transitions (06:00). Those shared the 06:00 minute before, so whether a
     *  coupon raised that morning was processed the same day was nondeterministic (T3-05). */
    @SchedulerLock(name = "couponPaymentJob", lockAtMostFor = "PT14M")
    @Scheduled(cron = "0 30 5 * * *", zone = "${registerwerk.register.time-zone:Europe/Berlin}")
    public void processDuePayments() {
        LocalDate today = registerClock.today();
        log.info("CouponPaymentJob: scanning announceable coupon payments for date={}", today);

        List<AssetCouponPayment> due = couponPaymentRepository
                .findAnnounceable(CouponStatus.SCHEDULED, today, today.plusDays(LEGACY_HORIZON_DAYS));

        for (AssetCouponPayment scanned : due) {
            UUID paymentId = scanned.getId();
            try {
                isolated.run(() -> raiseOne(paymentId, today));
            } catch (Exception e) {
                log.error("CouponPaymentJob: failed to create action for payment id={} (rolled back on its own; "
                        + "the other payments are unaffected): {}", paymentId, e.getMessage(), e);
            }
        }
        log.info("CouponPaymentJob: processed {} due coupon payments.", due.size());
    }

    private void raiseOne(UUID paymentId, LocalDate today) {
        AssetCouponPayment payment = couponPaymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            return;
        }
        // Idempotency: settlement confirms asynchronously (and only some token
        // standards flip the coupon to PAID), so the SCHEDULED row may still be
        // visible on the next run. Without this guard every daily run would
        // create another CorporateAction and pay the coupon to all holders again.
        if (corporateActionRepository.existsByCouponPaymentId(payment.getId())) {
            log.debug("CouponPaymentJob: action already exists for payment id={}, skipping.", payment.getId());
            return;
        }
        if (payment.getAmountPerUnit() == null) {
            // Floating-rate coupon without a rate fixing: raising it would snapshot
            // entitlements with no amount. Wait for the fixing (surfaced by the
            // registerwerk_coupon_awaiting_fixing gauge).
            log.warn("CouponPaymentJob: coupon payment id={} for assetId={} is due but has no fixed "
                    + "amount (floating rate awaiting fixing) — not raising.", payment.getId(), payment.getAssetId());
            return;
        }
        Optional<AssetBondTerms> terms = bondTermsRepository.findById(payment.getAssetId());
        if (payment.getAnnouncementDate() == null && !deriveLegacyDates(payment, terms.orElse(null), today)) {
            return; // legacy row whose derived announcement date is still in the future
        }
        if (terms.map(t -> t.getBondStatus() == BondStatus.CALLED || t.getBondStatus() == BondStatus.REDEEMED)
                .orElse(false)) {
            log.info("CouponPaymentJob: coupon payment id={} not raised — bond assetId={} is already {}.",
                    payment.getId(), payment.getAssetId(), terms.get().getBondStatus());
            return;
        }
        if (isTransferredOut(payment.getAssetId())) {
            log.info("Coupon payment id={} due for assetId={} but its register was transferred to a "
                    + "successor operator — not auto-raising a coupon action here.",
                    payment.getId(), payment.getAssetId());
            return;
        }
        CorporateAction action = new CorporateAction();
        action.setAssetId(payment.getAssetId());
        action.setActionType(CorporateAction.ActionType.COUPON);
        action.setAnnouncementDate(today);
        action.setRecordDate(payment.getRecordDate());
        action.setPaymentDate(payment.getScheduledDate());
        action.setBondPeriodStart(payment.getPeriodStart());
        action.setBondPeriodEnd(payment.getPeriodEnd());
        action.setCouponPaymentId(payment.getId());
        action.setInitiatedBy(SYSTEM_ACTOR);
        action.setNotes("Auto-created from coupon_payment id=" + payment.getId());

        // AssetCouponPayment.amountPerUnit is known at coupon-schedule creation time;
        // copy it onto the CorporateAction so the Steuerbescheinigung/position-statement
        // income columns aren't null placeholders.
        action.setAmountPerUnit(payment.getAmountPerUnit());
        terms.map(AssetBondTerms::getCurrencyIso).ifPresent(action::setCurrency);

        corporateActionService.announce(action);
        log.info("CouponPaymentJob: created CorporateAction for coupon payment id={}", payment.getId());
    }

    /**
     * A row generated before V12 has no record/announcement date: derive them with the schedule
     * defaults (record = payment − offset, announcement = record − lead, business days on the
     * bond's calendar) and persist them, so the row reads the same as a generated one.
     *
     * @return true when the derived announcement date has been reached
     */
    private boolean deriveLegacyDates(AssetCouponPayment payment, AssetBondTerms terms, LocalDate today) {
        HolidayCalendar holidays = terms != null && terms.getHolidayCalendar() != null
                ? terms.getHolidayCalendar() : HolidayCalendar.TARGET2;
        BusinessDayCalendar calendar = holidays.calendar();
        int recordOffset = terms != null ? terms.getRecordDateOffsetBd() : 1;
        int announceLead = terms != null ? terms.getAnnouncementLeadBd() : 5;
        LocalDate record = payment.getRecordDate() != null
                ? payment.getRecordDate() : calendar.addBusinessDays(payment.getScheduledDate(), -recordOffset);
        LocalDate announce = calendar.addBusinessDays(record, -announceLead);
        if (announce.isAfter(today)) {
            return false;
        }
        payment.setRecordDate(record);
        payment.setAnnouncementDate(announce);
        couponPaymentRepository.save(payment);
        return true;
    }

    private boolean isTransferredOut(UUID assetId) {
        return assetRepository.findById(assetId)
                .map(asset -> asset.getStatus() != null && asset.getStatus().isRegisterFrozen())
                .orElse(false);
    }
}
