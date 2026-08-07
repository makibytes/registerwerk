package de.makibytes.registerwerk.auth.api;

public enum AppUserRole {
    REGISTRY_ADMIN,
    AUDIT,
    COMPLIANCE_OFFICER,
    /** Operator staff with read-only access limited to their assigned client entities (F-BLOCKER-15) —
     *  see {@code LegalEntity.assignedRelationshipManagerId} and
     *  {@code EntityOwnershipChecker.isAssignedRelationshipManager}. Cannot approve, reject, or
     *  mutate anything a REGISTRY_ADMIN/COMPLIANCE_OFFICER can. */
    RELATIONSHIP_MANAGER,
    ISSUER,
    INVESTOR,
    COMPANY_ADMIN,
    TRADER,
    DAPP_PUBLISHER
}
