package de.makibytes.registerwerk.deployment.api;

/**
 * {@code OVERDUE} (T3-05): the payment date has passed and the coupon's corporate action is not
 * settled yet, but the interest grace period ({@code AssetBondTerms.interestGraceDays}) is still
 * running — operator-visible; customers see "payment pending". Only after the grace period does
 * it become {@code MISSED}. A coupon that settles moves straight to {@code PAID} from either.
 */
public enum CouponStatus {
    SCHEDULED,
    OVERDUE,
    PAID,
    MISSED
}
