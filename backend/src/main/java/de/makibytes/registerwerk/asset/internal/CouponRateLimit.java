package de.makibytes.registerwerk.asset.internal;

import java.math.BigDecimal;

/**
 * Plausibility bound for a bond {@code couponRate}. The rate is a FRACTION (0.042 = 4.2 %), so the DTO's
 * {@code @Digits(integer = 2)} alone accepts 99 = 9 900 %. The ceiling is configurable via
 * {@code registerwerk.corporate-actions.max-coupon-rate} (default 1 = 100 %).
 */
final class CouponRateLimit {

    static final BigDecimal DEFAULT_MAX = BigDecimal.ONE;

    private CouponRateLimit() {}

    /** @throws IllegalArgumentException (mapped to HTTP 400) when {@code rate} exceeds {@code max} */
    static void require(BigDecimal rate, BigDecimal max) {
        BigDecimal ceiling = max != null ? max : DEFAULT_MAX;
        if (rate != null && rate.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("couponRate " + rate.stripTrailingZeros().toPlainString()
                    + " exceeds the maximum of " + ceiling.stripTrailingZeros().toPlainString() + " ("
                    + ceiling.movePointRight(2).stripTrailingZeros().toPlainString()
                    + " %). The rate is a fraction: 0.042 means 4.2 %.");
        }
    }
}
