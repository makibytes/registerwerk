package de.makibytes.registerwerk.marketplace.api;

/** Lifecycle of a single manifest version within a listing. */
public enum DappVersionStatus {
    DRAFT,
    SUBMITTED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    SUPERSEDED
}
