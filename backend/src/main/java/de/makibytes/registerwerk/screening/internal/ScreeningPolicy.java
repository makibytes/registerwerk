package de.makibytes.registerwerk.screening.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Tunables of the screening controls (T6-03 / T6-04 interim values; the final numbers are the
 * operator's compliance decision). All are {@code registerwerk.screening.*} properties.
 */
@Component
public class ScreeningPolicy {

    private final int acceptValidityDays;
    private final BigDecimal scoreTolerance;
    private final Duration staleClearGrace;
    private final Duration staleClearMaxAge;
    private final int retryMaxAttempts;

    public ScreeningPolicy(
            @Value("${registerwerk.screening.accept-validity-days:90}") int acceptValidityDays,
            @Value("${registerwerk.screening.carry-forward-score-tolerance:0.05}") BigDecimal scoreTolerance,
            @Value("${registerwerk.screening.stale-clear-grace-hours:24}") int staleClearGraceHours,
            @Value("${registerwerk.screening.stale-clear-max-age-hours:72}") int staleClearMaxAgeHours,
            @Value("${registerwerk.screening.retry-max-attempts:6}") int retryMaxAttempts) {
        this.acceptValidityDays = acceptValidityDays;
        this.scoreTolerance = scoreTolerance;
        this.staleClearGrace = Duration.ofHours(staleClearGraceHours);
        this.staleClearMaxAge = Duration.ofHours(Math.max(staleClearMaxAgeHours, staleClearGraceHours));
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public static ScreeningPolicy defaults() {
        return new ScreeningPolicy(90, new BigDecimal("0.05"), 24, 72, 6);
    }

    /** How long an accepted false positive may be carried onto new runs (counted from the original decision). */
    public int acceptValidityDays() { return acceptValidityDays; }

    /** A re-found match scoring more than the accepted score plus this tolerance is reviewed again. */
    public BigDecimal scoreTolerance() { return scoreTolerance; }

    /** How long after the first failed run the previous good result is still relied on. */
    public Duration staleClearGrace() { return staleClearGrace; }

    /** Hard cap on the age of the good result itself, whatever the outage length. */
    public Duration staleClearMaxAge() { return staleClearMaxAge; }

    public int retryMaxAttempts() { return retryMaxAttempts; }
}
