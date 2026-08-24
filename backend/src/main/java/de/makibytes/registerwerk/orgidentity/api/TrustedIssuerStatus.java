package de.makibytes.registerwerk.orgidentity.api;

/** Lifecycle of an ecosystem-wide trusted issuer mirror. */
public enum TrustedIssuerStatus {
    /** Addition intent submitted, not yet confirmed. */
    PENDING,
    /** Addition is confirmed onchain. */
    ACTIVE,
    /** Removal intent is fail-closed locally while awaiting chain finality. */
    REMOVAL_PENDING,
    /** Removal is confirmed onchain. */
    REMOVED,
    /** Removal transaction failed; trust stays disabled until an explicit retry. */
    REMOVAL_FAILED,
    /** Addition transaction failed. */
    FAILED
}
