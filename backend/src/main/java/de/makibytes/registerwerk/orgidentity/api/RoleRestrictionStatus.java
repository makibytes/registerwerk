package de.makibytes.registerwerk.orgidentity.api;

/** Receipt/finality lifecycle of an ORG permission grant's role-restriction flag. */
public enum RoleRestrictionStatus {
    /** {@code roleRestricted} is the last confirmed on-chain value. */
    STABLE,
    /** A desired value has been submitted (or awaits submission) and access is fail-closed. */
    CHANGE_PENDING,
    /** The submitted change reverted at finality; an explicit retry is required. */
    CHANGE_FAILED
}
