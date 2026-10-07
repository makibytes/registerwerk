package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

/**
 * Money rules of the repo desk (5B-06): ISO 4217 minor units and the money-market day-count basis per
 * currency. ACT/360 is the default; the currencies below conventionally use ACT/365 (fixed).
 * Interest is rounded HALF_UP to the currency's minor unit and the repurchase amount stored rounded.
 */
final class CurrencyRules {
    private static final Set<String> ACT_365 = Set.of("GBP", "AUD", "CAD", "NZD", "HKD", "SGD", "ZAR", "JPY", "PLN", "THB", "INR");

    private CurrencyRules() {}

    static int dayCountBasis(String code) {
        return ACT_365.contains(code.toUpperCase(Locale.ROOT)) ? 365 : 360;
    }

    static void requireMinorUnitScale(String code, BigDecimal amount, String field) {
        if (amount.stripTrailingZeros().scale() > Money.minorUnits(code)) {
            throw new IllegalArgumentException(field + " has more decimals than " + code + " allows ("
                    + Money.minorUnits(code) + ")");
        }
    }

    /** Repurchase amount = cash + cash * rate% * days / basis, interest rounded to the minor unit. */
    static BigDecimal repurchaseAmount(String code, BigDecimal cash, BigDecimal ratePercent,
                                       LocalDate start, LocalDate end, int basis) {
        long days = ChronoUnit.DAYS.between(start, end);
        BigDecimal interest = cash.multiply(ratePercent).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(basis).multiply(BigDecimal.valueOf(100)), 18, RoundingMode.HALF_UP);
        return Money.round(cash.add(Money.round(interest, code)), code);
    }
}
