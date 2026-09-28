package de.makibytes.registerwerk.deployment.api.schedule;

import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates a fixed-income coupon schedule the ICMA way: regular dates are rolled
 * <em>backward</em> from maturity, so any irregular period ends up at the start
 * ({@link StubRule#SHORT_FIRST}). If maturity is the last day of its month, every regular date is
 * the last day of its month too (end-of-month rule). Accrual uses unadjusted dates; payment
 * dates are adjusted by the business-day convention on the given calendar.
 */
public final class CouponScheduleCalculator {

    /** Parameters of one schedule. Null conventions fall back to the ICMA/TARGET2 defaults. */
    public record Terms(
            LocalDate issueDate,
            LocalDate maturityDate,
            PaymentFrequency frequency,
            DayCountConvention dayCount,
            StubRule stubRule,
            BusinessDayConvention businessDayConvention,
            BusinessDayCalendar calendar,
            int recordOffsetBd,
            int announceLeadBd) {
    }

    private CouponScheduleCalculator() {}

    /** Number of months in one regular period, or 0 for a zero-coupon bond. */
    public static int monthsPerPeriod(PaymentFrequency frequency) {
        return switch (frequency) {
            case ANNUAL -> 12;
            case SEMI_ANNUAL -> 6;
            case QUARTERLY -> 3;
            case MONTHLY -> 1;
            case ZERO -> 0;
        };
    }

    public static List<CouponPeriod> generate(Terms t) {
        if (t.issueDate() == null || t.maturityDate() == null || !t.maturityDate().isAfter(t.issueDate())) {
            throw new IllegalArgumentException("maturityDate must be after issueDate");
        }
        if (t.recordOffsetBd() < 0 || t.announceLeadBd() < 0) {
            throw new IllegalArgumentException("record-date offset and announcement lead must be >= 0");
        }
        int months = t.frequency() == null ? 0 : monthsPerPeriod(t.frequency());
        if (months == 0) {
            return List.of();
        }
        int periodsPerYear = 12 / months;
        DayCountConvention dayCount = t.dayCount() != null ? t.dayCount() : DayCountConvention.ACT_ACT_ICMA;
        BusinessDayConvention bdc = t.businessDayConvention() != null
                ? t.businessDayConvention() : BusinessDayConvention.MODIFIED_FOLLOWING;
        BusinessDayCalendar calendar = t.calendar() != null ? t.calendar() : Target2Calendar.INSTANCE;
        boolean endOfMonth = t.maturityDate().equals(t.maturityDate().with(TemporalAdjusters.lastDayOfMonth()));

        // Unadjusted regular dates, newest first: maturity, maturity − 1 period, … until the
        // first date on or before the issue date (that one is only the notional start of the
        // first period — equal to the issue date when there is no stub).
        List<LocalDate> ends = new ArrayList<>();
        LocalDate notionalFirstStart;
        for (int k = 0; ; k++) {
            LocalDate d = rollBack(t.maturityDate(), (long) k * months, endOfMonth);
            if (!d.isAfter(t.issueDate())) {
                notionalFirstStart = d;
                break;
            }
            ends.add(d);
        }
        Collections.reverse(ends);

        List<CouponPeriod> periods = new ArrayList<>(ends.size());
        LocalDate start = t.issueDate();
        for (int i = 0; i < ends.size(); i++) {
            LocalDate end = ends.get(i);
            boolean stub = i == 0 && !notionalFirstStart.equals(t.issueDate());
            LocalDate payment = calendar.adjust(end, bdc);
            LocalDate record = calendar.addBusinessDays(payment, -t.recordOffsetBd());
            LocalDate announce = calendar.addBusinessDays(record, -t.announceLeadBd());
            periods.add(new CouponPeriod(i + 1, start, end, payment, record, announce,
                    DayCountFractions.fraction(dayCount, start, end, stub ? notionalFirstStart : start, periodsPerYear),
                    stub));
            start = end;
        }
        return periods;
    }

    /** Always derived from maturity (not from the previous date) so short months never drift the day. */
    private static LocalDate rollBack(LocalDate maturity, long monthsBack, boolean endOfMonth) {
        LocalDate d = maturity.minusMonths(monthsBack);
        return endOfMonth ? d.with(TemporalAdjusters.lastDayOfMonth()) : d;
    }
}
