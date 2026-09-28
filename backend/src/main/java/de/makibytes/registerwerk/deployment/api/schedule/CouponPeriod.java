package de.makibytes.registerwerk.deployment.api.schedule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One generated coupon period.
 *
 * @param periodNo         1-based, counted from the issue date
 * @param periodStart      accrual start (unadjusted; the issue date for the first period)
 * @param periodEnd        accrual end = unadjusted scheduled payment date
 * @param paymentDate      {@code periodEnd} adjusted by the business-day convention
 * @param recordDate       {@code paymentDate} minus the record-date offset (business days)
 * @param announcementDate {@code recordDate} minus the announcement lead time (business days)
 * @param dayCountFraction accrual year fraction, unrounded (scale 18)
 * @param stub             true for an irregular (short first) period
 */
public record CouponPeriod(
        int periodNo,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate paymentDate,
        LocalDate recordDate,
        LocalDate announcementDate,
        BigDecimal dayCountFraction,
        boolean stub) {
}
