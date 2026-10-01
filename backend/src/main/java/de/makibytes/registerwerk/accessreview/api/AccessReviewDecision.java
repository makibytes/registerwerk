package de.makibytes.registerwerk.accessreview.api;

public enum AccessReviewDecision {
    PENDING,
    /** Reviewer attests the account's snapshotted roles are still appropriate. */
    CONFIRMED,
    /** Reviewer attests the account's access should be revoked — the account is disabled as
     *  part of recording this decision, not left as a paper finding with no effect. */
    REVOKED,
    /** First reviewer asked to revoke a privileged account (REGISTRY_ADMIN, COMPLIANCE_OFFICER,
     *  COMPANY_ADMIN); takes effect only when a second, different reviewer confirms with REVOKED. */
    REVOKE_PROPOSED,
    /** The account's roles or enabled flag changed after the snapshot: the decision is void and
     *  the item must be re-opened (re-snapshotted) and re-reviewed. */
    STALE
}
