package de.makibytes.registerwerk.deployment.api;

/**
 * {@code OVERDUE} (T3-05): the redemption payment date has passed without settlement, but the
 * principal grace period ({@code AssetBondTerms.principalGraceDays}) is still running —
 * operator-visible; customers see "payment pending", never "default". Only after the grace period
 * does the bond become {@code DEFAULTED}. A settled REDEMPTION moves the bond to {@code REDEEMED}
 * (also from OVERDUE/DEFAULTED); a settled CALL moves it to {@code CALLED}.
 */
public enum BondStatus {
    ACTIVE,
    MATURED,
    OVERDUE,
    CALLED,
    DEFAULTED,
    REDEEMED
}
