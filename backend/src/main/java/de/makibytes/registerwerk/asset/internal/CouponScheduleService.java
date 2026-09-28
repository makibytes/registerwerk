package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.CouponPaymentActionLookup;
import de.makibytes.registerwerk.asset.events.AssetIssuedEvent;
import de.makibytes.registerwerk.asset.events.CouponScheduleGeneratedEvent;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.schedule.CouponPeriod;
import de.makibytes.registerwerk.deployment.api.schedule.CouponScheduleCalculator;
import de.makibytes.registerwerk.deployment.api.schedule.DayCountFractions;
import de.makibytes.registerwerk.deployment.api.schedule.HolidayCalendar;
import de.makibytes.registerwerk.shared.RegisterClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns a bond's terms into {@code asset_coupon_payment} rows — the rows {@code CouponPaymentJob}
 * raises COUPON corporate actions from. Before this existed nothing ever wrote a SCHEDULED row, so
 * no coupon could ever be raised (T3-04).
 *
 * <p>Regeneration rules: only SCHEDULED rows with a payment date on or after today and no
 * corporate action yet are replaced. PAID/MISSED rows, past rows and rows an action was already
 * raised for are immutable history. New rows are only written for future payment dates, so
 * entering terms late (or the backfill) never creates retroactive coupons.
 *
 * <p>{@code amountPerUnit = faceValue × couponRate × dayCountFraction}, unrounded (scale 18);
 * rounding happens only per holder entitlement. Floating-rate rows get a null amount until a
 * fixing sets it, and the job does not raise them before that.
 */
@Service
public class CouponScheduleService {

    private static final Logger log = LoggerFactory.getLogger(CouponScheduleService.class);

    public static final String TRIGGER_BOND_TERMS = "BOND_TERMS";
    public static final String TRIGGER_ASSET_ISSUED = "ASSET_ISSUED";
    public static final String TRIGGER_TERMS_AMENDMENT = "TERMS_AMENDMENT";
    public static final String TRIGGER_BACKFILL = "BACKFILL";

    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CouponPaymentActionLookup actionLookup;
    private final ApplicationEventPublisher events;
    private final RegisterClock registerClock;

    public CouponScheduleService(AssetBondTermsRepository bondTermsRepository,
                                 AssetCouponPaymentRepository couponPaymentRepository,
                                 CouponPaymentActionLookup actionLookup,
                                 ApplicationEventPublisher events,
                                 RegisterClock registerClock,
                                 MeterRegistry meterRegistry) {
        this.bondTermsRepository = bondTermsRepository;
        this.couponPaymentRepository = couponPaymentRepository;
        this.actionLookup = actionLookup;
        this.events = events;
        this.registerClock = registerClock;
        if (meterRegistry != null) {
            Gauge.builder("registerwerk_coupon_awaiting_fixing", couponPaymentRepository,
                            repo -> repo.countByCouponStatusAndAmountPerUnitIsNullAndScheduledDateLessThanEqual(
                                    CouponStatus.SCHEDULED, registerClock.today()))
                    .description("Due floating-rate coupons that cannot be raised because no rate fixing set their amount")
                    .register(meterRegistry);
        }
    }

    /** True when the terms produce periodic coupons (fixed rate &gt; 0, or a floating reference rate). */
    static boolean paysCoupons(AssetBondTerms terms) {
        if (CouponScheduleCalculator.monthsPerPeriod(terms.getPaymentFrequency()) == 0) {
            return false;
        }
        return terms.getReferenceRate() != null
                || (terms.getCouponRate() != null && terms.getCouponRate().signum() > 0);
    }

    /** Computes the periods for these terms without touching the database. */
    public static List<CouponPeriod> periods(AssetBondTerms terms) {
        if (!paysCoupons(terms)) {
            return List.of();
        }
        HolidayCalendar calendar = terms.getHolidayCalendar() != null ? terms.getHolidayCalendar() : HolidayCalendar.TARGET2;
        return CouponScheduleCalculator.generate(new CouponScheduleCalculator.Terms(
                terms.getIssueDate(), terms.getMaturityDate(), terms.getPaymentFrequency(), terms.getDayCount(),
                terms.getStubRule(), terms.getBusinessDayConvention(), calendar.calendar(),
                terms.getRecordDateOffsetBd(), terms.getAnnouncementLeadBd()));
    }

    /** Fixed coupons: faceValue × rate × dcf (unrounded). Floating coupons: null until fixed. */
    static BigDecimal amountPerUnit(AssetBondTerms terms, BigDecimal dayCountFraction) {
        if (terms.getReferenceRate() != null || terms.getCouponRate() == null) {
            return null;
        }
        return terms.getFaceValue().multiply(terms.getCouponRate()).multiply(dayCountFraction)
                .setScale(DayCountFractions.SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * (Re)generates the schedule for one bond. No-op (returns 0) when the asset has no terms.
     *
     * @return number of rows written
     */
    @Transactional
    public int regenerate(UUID assetId, UUID actorId, String actorRole, String trigger) {
        AssetBondTerms terms = bondTermsRepository.findById(assetId).orElse(null);
        if (terms == null) {
            return 0;
        }
        LocalDate today = registerClock.today();
        List<AssetCouponPayment> existing = couponPaymentRepository.findByAssetIdOrderByPeriodNo(assetId);

        List<AssetCouponPayment> replaceable = new ArrayList<>();
        LocalDate keptUntil = null;
        int version = 0;
        for (AssetCouponPayment row : existing) {
            version = Math.max(version, row.getScheduleVersion());
            boolean replace = row.getCouponStatus() == CouponStatus.SCHEDULED
                    && !row.getScheduledDate().isBefore(today)
                    && !actionLookup.hasCorporateAction(row.getId());
            if (replace) {
                replaceable.add(row);
            } else {
                LocalDate end = row.getPeriodEnd() != null ? row.getPeriodEnd() : row.getScheduledDate();
                if (keptUntil == null || end.isAfter(keptUntil)) {
                    keptUntil = end;
                }
            }
        }
        int nextVersion = version + 1;

        List<AssetCouponPayment> created = new ArrayList<>();
        for (CouponPeriod p : periods(terms)) {
            if (p.paymentDate().isBefore(today)) {
                continue;
            }
            if (keptUntil != null && !p.periodEnd().isAfter(keptUntil)) {
                continue; // an immutable row (paid, missed or already raised) covers this period
            }
            AssetCouponPayment row = new AssetCouponPayment();
            row.setAssetId(assetId);
            row.setPeriodNo(p.periodNo());
            row.setPeriodStart(p.periodStart());
            row.setPeriodEnd(p.periodEnd());
            row.setUnadjustedDate(p.periodEnd());
            row.setScheduledDate(p.paymentDate());
            row.setRecordDate(p.recordDate());
            row.setAnnouncementDate(p.announcementDate());
            row.setDayCountFraction(p.dayCountFraction());
            row.setAmountPerUnit(amountPerUnit(terms, p.dayCountFraction()));
            row.setCouponStatus(CouponStatus.SCHEDULED);
            row.setScheduleVersion(nextVersion);
            created.add(row);
        }

        if (replaceable.isEmpty() && created.isEmpty()) {
            return 0;
        }
        renumberOnCollision(existing, replaceable, created);
        couponPaymentRepository.deleteAll(replaceable);
        couponPaymentRepository.flush();
        couponPaymentRepository.saveAll(created);
        events.publishEvent(new CouponScheduleGeneratedEvent(assetId, actorId, actorRole,
                nextVersion, created.size(), replaceable.size(), trigger));
        log.info("Coupon schedule v{} for asset={} ({}): {} row(s) written, {} replaced",
                nextVersion, assetId, trigger, created.size(), replaceable.size());
        return created.size();
    }

    /**
     * After an amendment that moves maturity the fresh generation restarts its numbering, which can
     * collide with an immutable (PAID/raised) row; there is no unique index to catch it, so shift
     * the new rows behind the highest kept number (they all lie after the kept periods).
     */
    private static void renumberOnCollision(List<AssetCouponPayment> existing,
                                            List<AssetCouponPayment> replaceable,
                                            List<AssetCouponPayment> created) {
        java.util.Set<Integer> keptNos = new java.util.HashSet<>();
        for (AssetCouponPayment row : existing) {
            if (!replaceable.contains(row)) {
                keptNos.add(row.getPeriodNo());
            }
        }
        if (created.stream().noneMatch(r -> keptNos.contains(r.getPeriodNo()))) {
            return;
        }
        int next = keptNos.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        for (AssetCouponPayment row : created) {
            row.setPeriodNo(next++);
        }
    }

    @Transactional(readOnly = true)
    public List<AssetCouponPayment> schedule(UUID assetId) {
        return couponPaymentRepository.findByAssetIdOrderByPeriodNo(assetId);
    }

    /** Terms entered before issuance: make sure the schedule reflects them once the asset is issued. */
    @ApplicationModuleListener
    void onAssetIssued(AssetIssuedEvent event) {
        if (bondTermsRepository.existsById(event.assetId())) {
            regenerate(event.assetId(), event.actorId(), event.actorRole(), TRIGGER_ASSET_ISSUED);
        }
    }

    /** Used by the backfill: bonds that pay coupons but have no schedule rows at all. */
    @Transactional(readOnly = true)
    public List<UUID> bondsMissingSchedule() {
        return bondTermsRepository.findAll().stream()
                .filter(t -> t.getCouponRate() != null && t.getCouponRate().signum() > 0)
                .filter(CouponScheduleService::paysCoupons)
                .map(AssetBondTerms::getAssetId)
                .filter(Objects::nonNull)
                .filter(id -> !couponPaymentRepository.existsByAssetId(id))
                .toList();
    }
}
