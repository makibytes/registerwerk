package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.shared.ProductionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Forward-pricing (T1-07) configuration of ERC-7540 vaults: the default dealing cut-off sent after every
 * vault deployment ({@code registerwerk.vault.dealing-cutoff-utc}, default {@code 17:00} UTC = 61200 s, and
 * {@code registerwerk.vault.dealing-period-seconds}, default 86400) and the production-mode switch that makes
 * an unconfigured vault un-subscribable. A blank / {@code off} cut-off disables the automatic configuration
 * (a demo vault then keeps the legacy "settle at the NAV struck at execution time" behaviour).
 */
@Component
class VaultDealingSettings {

    /** Operator-set periods outside this range are refused: a dealing period shorter than an hour or longer than a month is a typo. */
    static final long MIN_PERIOD_SECONDS = 3_600L;
    static final long MAX_PERIOD_SECONDS = 31L * 86_400L;
    static final int SECONDS_PER_DAY = 86_400;

    private final boolean production;
    private final Integer defaultCutoffSecondsOfDay;
    private final long defaultPeriodSeconds;

    VaultDealingSettings(
            Environment environment,
            @Value("${registerwerk.vault.dealing-cutoff-utc:17:00}") String cutoffUtc,
            @Value("${registerwerk.vault.dealing-period-seconds:86400}") long periodSeconds) {
        this.production = ProductionMode.resolve(environment);
        this.defaultCutoffSecondsOfDay = parseCutoff(cutoffUtc);
        if (periodSeconds < MIN_PERIOD_SECONDS || periodSeconds > MAX_PERIOD_SECONDS) {
            throw new IllegalStateException("registerwerk.vault.dealing-period-seconds must be between "
                    + MIN_PERIOD_SECONDS + " and " + MAX_PERIOD_SECONDS + " seconds, was " + periodSeconds);
        }
        this.defaultPeriodSeconds = periodSeconds;
    }

    static Integer parseCutoff(String value) {
        if (value == null || value.isBlank() || "off".equalsIgnoreCase(value.trim())
                || "none".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim()).toSecondOfDay();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("registerwerk.vault.dealing-cutoff-utc must be HH:mm[:ss] (UTC) or "
                    + "'off', was '" + value + "'", e);
        }
    }

    /** "HH:mm" (or "HH:mm:ss" when seconds are set) of a seconds-of-day value. */
    static String formatCutoff(long secondsOfDay) {
        LocalTime t = LocalTime.ofSecondOfDay(secondsOfDay);
        return t.getSecond() == 0 ? String.format("%02d:%02d", t.getHour(), t.getMinute()) : t.toString();
    }

    /** Range check shared by the operator endpoint (also enforced by the contract: cut-off &lt; 86400, period &gt; 0). */
    static void requireValid(long cutoffSecondsOfDay, long periodSeconds) {
        if (cutoffSecondsOfDay < 0 || cutoffSecondsOfDay >= SECONDS_PER_DAY) {
            throw new IllegalArgumentException("The dealing cut-off must be a time of day between 00:00 and 23:59:59 UTC");
        }
        if (periodSeconds < MIN_PERIOD_SECONDS || periodSeconds > MAX_PERIOD_SECONDS) {
            throw new IllegalArgumentException("The dealing period must be between " + MIN_PERIOD_SECONDS
                    + " and " + MAX_PERIOD_SECONDS + " seconds");
        }
    }

    boolean production() { return production; }

    /** True when every new vault deployment is configured automatically. */
    boolean autoConfigure() { return defaultCutoffSecondsOfDay != null; }

    int defaultCutoffSecondsOfDay() {
        if (defaultCutoffSecondsOfDay == null) {
            throw new IllegalStateException("automatic dealing cut-off configuration is disabled");
        }
        return defaultCutoffSecondsOfDay;
    }

    long defaultPeriodSeconds() { return defaultPeriodSeconds; }
}
