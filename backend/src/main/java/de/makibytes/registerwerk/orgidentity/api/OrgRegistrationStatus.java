package de.makibytes.registerwerk.orgidentity.api;

/** Lifecycle of an organization's onchain registration (OrgRegistry mirror). */
public enum OrgRegistrationStatus {
    /** Registration submitted (or waiting for the ONCHAINID to resolve); not yet confirmed. */
    PENDING,
    /** registerOrg confirmed onchain; members can be bound. */
    ACTIVE,
    /** suspendOrg confirmed; members lose active-member semantics until reinstated. */
    SUSPENDED,
    /** The registration transaction failed onchain. */
    FAILED
}
