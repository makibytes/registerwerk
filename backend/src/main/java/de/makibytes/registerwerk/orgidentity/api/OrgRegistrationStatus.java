package de.makibytes.registerwerk.orgidentity.api;

/** Lifecycle of an organization's onchain registration (OrgRegistry mirror). */
public enum OrgRegistrationStatus {
    /** Registration submitted (or waiting for the ONCHAINID to resolve); not yet confirmed. */
    PENDING,
    /** registerOrg confirmed onchain; members can be bound. */
    ACTIVE,
    /** A suspend intent is durable but has not reached chain finality; access is denied. */
    SUSPEND_PENDING,
    /** suspendOrg confirmed; members lose active-member semantics until reinstated. */
    SUSPENDED,
    /** A finalized suspend transaction reverted; only an explicit retry may proceed. */
    SUSPEND_FAILED,
    /** A reinstate intent is durable but has not reached chain finality; access remains denied. */
    REINSTATE_PENDING,
    /** A finalized reinstate transaction reverted; access remains denied until retry. */
    REINSTATE_FAILED,
    /** The registration transaction failed onchain. */
    FAILED
}
