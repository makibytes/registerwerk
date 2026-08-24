package de.makibytes.registerwerk.orgidentity.api;

public enum PermissionGrantStatus {
    PENDING,
    ACTIVE,
    /** A revoke was requested and remains fail-closed while its transaction reaches finality. */
    REVOCATION_PENDING,
    REVOKED,
    /** The revoke transaction failed; access remains disabled until an explicit retry. */
    REVOCATION_FAILED,
    FAILED
}
