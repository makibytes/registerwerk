package de.makibytes.registerwerk.deployment.api.schedule;

/**
 * How a scheduled date that falls on a non-business day is moved (ISDA / ICMA terminology).
 * Accrual periods always run on the <em>unadjusted</em> dates; only the payment date moves.
 */
public enum BusinessDayConvention {
    /** Next business day. */
    FOLLOWING,
    /** Next business day, unless that crosses into the next month — then the previous one. */
    MODIFIED_FOLLOWING,
    /** Previous business day. */
    PRECEDING,
    /** No adjustment — pay on the calendar date even if it is a holiday. */
    NONE
}
