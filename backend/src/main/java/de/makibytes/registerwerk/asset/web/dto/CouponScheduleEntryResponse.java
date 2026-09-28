package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of a bond's coupon schedule. {@code amountPerUnit} is null for an unfixed floating coupon. */
public record CouponScheduleEntryResponse(
        int periodNo,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate announcementDate,
        LocalDate recordDate,
        LocalDate paymentDate,
        BigDecimal dayCountFraction,
        BigDecimal amountPerUnit,
        String couponStatus,
        LocalDate paidDate,
        int scheduleVersion) {

    public static CouponScheduleEntryResponse from(AssetCouponPayment p) {
        return new CouponScheduleEntryResponse(p.getPeriodNo(), p.getPeriodStart(), p.getPeriodEnd(),
                p.getAnnouncementDate(), p.getRecordDate(), p.getScheduledDate(), p.getDayCountFraction(),
                p.getAmountPerUnit(), p.getCouponStatus().name(), p.getPaidDate(), p.getScheduleVersion());
    }
}
