package de.makibytes.registerwerk.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * The one place monetary amounts get their minor unit and their rounding (Wave 5a). Before this there were
 * four helpers that disagreed: corporate actions fell back to 2 digits for an unknown currency and rounded
 * {@code HALF_EVEN}, trading rounded {@code HALF_EVEN}, repo and subscriptions rounded {@code HALF_UP}.
 *
 * <p><strong>Policy.</strong> An amount that is paid out or charged (coupon / redemption entitlements,
 * subscription amounts, repo and trade cash amounts) is rounded to the currency's ISO 4217 minor unit with
 * {@link RoundingMode#HALF_UP}: commercial rounding (German <em>kaufmaennische Rundung</em>; ICMA Rule 251
 * rounds a half-unit up). A currency that is missing, unknown or has no minor unit (pseudo codes such as
 * {@code XXX}) is refused with {@link IllegalArgumentException} - never guessed - because a wrong scale
 * silently mis-pays every holder. Non-monetary ratios and rates (day-count fractions, basis points, price
 * deviation) keep their own {@code scale}/{@code MathContext} and do not go through here.
 */
public final class Money {

    /** The one rounding mode for paid-out / charged amounts. */
    public static final RoundingMode MODE = RoundingMode.HALF_UP;

    private Money() {
    }

    /** ISO 4217 minor-unit digits (EUR 2, JPY 0); throws for a null, blank, unknown or minor-unit-less code. */
    public static int minorUnits(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + currency);
        }
        int digits;
        try {
            digits = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency: " + currency);
        }
        if (digits < 0) {
            throw new IllegalArgumentException("ISO 4217 currency without a minor unit: " + currency);
        }
        return digits;
    }

    /** {@code amount} rounded {@link #MODE} to the minor unit of {@code currency}. */
    public static BigDecimal round(BigDecimal amount, String currency) {
        return round(amount, minorUnits(currency));
    }

    /** {@code amount} rounded {@link #MODE} to {@code scale} decimals (e.g. a stablecoin rail's decimals). */
    public static BigDecimal round(BigDecimal amount, int scale) {
        return amount.setScale(scale, MODE);
    }
}
