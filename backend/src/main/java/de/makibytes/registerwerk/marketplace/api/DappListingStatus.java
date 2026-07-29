package de.makibytes.registerwerk.marketplace.api;

/** Lifecycle of a marketplace listing (overall state across its versions). */
public enum DappListingStatus {
    DRAFT,
    SUBMITTED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    DEPRECATED,
    DELISTED
}
