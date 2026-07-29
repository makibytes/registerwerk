package de.makibytes.registerwerk.orgidentity.api;

public enum PermissionGrantType {
    /** Operator-issued grant to an organization. */
    ORG,
    /** Org-admin delegation of a granted permission to an org-scoped role. */
    ROLE
}
