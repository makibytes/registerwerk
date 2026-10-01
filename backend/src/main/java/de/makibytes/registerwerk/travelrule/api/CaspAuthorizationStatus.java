package de.makibytes.registerwerk.travelrule.api;

/**
 * MiCA authorization status of a counterparty CASP (Reg (EU) 2023/1114).
 * The EU-wide transitional period ends on 1 July 2026; from that date a
 * {@link #TRANSITIONAL} counterparty is treated like {@link #NOT_AUTHORIZED}.
 */
public enum CaspAuthorizationStatus {
    /** Holds a MiCA CASP authorization (listed in the ESMA register). */
    AUTHORIZED,
    /** Operating under a national transitional (grandfathering) regime. */
    TRANSITIONAL,
    /** Confirmed to hold no authorization and no transitional status. */
    NOT_AUTHORIZED,
    /** Authorization was withdrawn by the competent authority. */
    REVOKED,
    /**
     * Non-EU / third-country VASP for which an operator recorded an explicit due-diligence review
     * (reviewer + expiry in {@code validUntil}, created with step-up and a second approver). Without
     * such a row an unknown counterparty is blocked after the enforcement date (T6-07).
     */
    THIRD_COUNTRY_REVIEWED
}
