package de.makibytes.registerwerk.trading.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * The one place trade totals are rounded (Phase 5, 5A-02). Unit prices keep their scale; the
 * executed total is {@code round(unitPrice * quantity)} to the currency's minor unit with
 * {@link RoundingMode#HALF_EVEN} (banker's rounding, unbiased across many trades). ISO 4217
 * currencies use their standard fraction digits (EUR 2, JPY 0); a stablecoin rail uses its token
 * decimals capped at {@link #STABLECOIN_MAX_SCALE} for display. The exact product and the applied
 * scale/mode are stored on the execution so the rounding is reproducible.
 */
final class Money {

    static final RoundingMode MODE = RoundingMode.HALF_EVEN;
    static final int STABLECOIN_MAX_SCALE = 6;

    private Money() {
    }

    /** Minor-unit digits of an ISO 4217 code; throws {@link IllegalArgumentException} for an unknown code. */
    static int minorUnits(String isoCurrency) {
        try {
            int digits = Currency.getInstance(isoCurrency).getDefaultFractionDigits();
            return Math.max(digits, 0);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + isoCurrency);
        }
    }

    /** Rounding scale for a stablecoin rail: its decimals (default 6), capped at 6. */
    static int stablecoinScale(Integer railDecimals) {
        int d = railDecimals != null ? railDecimals : STABLECOIN_MAX_SCALE;
        return Math.max(0, Math.min(d, STABLECOIN_MAX_SCALE));
    }

    static BigDecimal round(BigDecimal amount, int scale) {
        return amount.setScale(scale, MODE);
    }
}
