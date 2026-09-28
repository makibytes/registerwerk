package de.makibytes.registerwerk.deployment.api.schedule;

import de.makibytes.registerwerk.deployment.api.DayCountConvention;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Year fractions for one accrual period, per day-count convention. Results carry
 * {@link #SCALE} decimal places and are deliberately not rounded further — rounding to the
 * currency's minor unit happens only on a holder's entitlement, never on the per-unit rate.
 */
public final class DayCountFractions {

    public static final int SCALE = 18;

    private DayCountFractions() {}

    /**
     * @param start          accrual start (unadjusted)
     * @param end            accrual end (unadjusted)
     * @param notionalStart  start of the regular (notional) period ending at {@code end}; equal to
     *                       {@code start} for a regular period. Only ACT/ACT-ICMA uses it.
     * @param periodsPerYear coupon frequency (1, 2, 4, 12). Only ACT/ACT-ICMA uses it.
     */
    public static BigDecimal fraction(DayCountConvention convention, LocalDate start, LocalDate end,
                                      LocalDate notionalStart, int periodsPerYear) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("accrual end must be after start: " + start + " → " + end);
        }
        return switch (convention) {
            case ACT_ACT_ICMA -> actActIcma(start, end, notionalStart, periodsPerYear);
            case ACT_360 -> divide(days(start, end), 360);
            case ACT_365 -> divide(days(start, end), 365);
            case THIRTY_360 -> divide(thirty360Us(start, end), 360);
            case THIRTY_E_360 -> divide(thirtyE360(start, end), 360);
        };
    }

    /**
     * ICMA Rule 251: a regular period accrues exactly 1/f; an irregular (stub) period accrues
     * {@code days / (f × days in the notional regular period)}.
     */
    private static BigDecimal actActIcma(LocalDate start, LocalDate end, LocalDate notionalStart, int f) {
        if (f <= 0) {
            throw new IllegalArgumentException("ACT/ACT-ICMA requires a periodic coupon frequency");
        }
        if (notionalStart == null || notionalStart.equals(start)) {
            return BigDecimal.ONE.divide(BigDecimal.valueOf(f), SCALE, RoundingMode.HALF_EVEN);
        }
        return BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf((long) f * days(notionalStart, end)), SCALE, RoundingMode.HALF_EVEN);
    }

    /** 30/360 US bond basis: D1=31→30; D2=31→30 only when D1 is (now) 30. */
    private static long thirty360Us(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 == 30) {
            d2 = 30;
        }
        return thirty(start, end, d1, d2);
    }

    /** 30E/360 (Eurobond basis): every 31st counts as the 30th. */
    private static long thirtyE360(LocalDate start, LocalDate end) {
        return thirty(start, end, Math.min(start.getDayOfMonth(), 30), Math.min(end.getDayOfMonth(), 30));
    }

    private static long thirty(LocalDate start, LocalDate end, int d1, int d2) {
        return 360L * (end.getYear() - start.getYear())
                + 30L * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
    }

    private static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    private static BigDecimal divide(long numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_EVEN);
    }
}
