package de.makibytes.registerwerk.deployment.api.schedule;

/**
 * Where an irregular period goes when issue and maturity are not a whole number of coupon
 * periods apart. The schedule is always rolled backward from maturity.
 */
public enum StubRule {
    /** The first period (issue date → first regular date) is shorter than a regular period. */
    SHORT_FIRST
}
