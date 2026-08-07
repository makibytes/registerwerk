package de.makibytes.registerwerk.accessreview.api;

public enum AccessReviewDecision {
    PENDING,
    /** Reviewer attests the account's snapshotted roles are still appropriate. */
    CONFIRMED,
    /** Reviewer attests the account's access should be revoked — the account is disabled as
     *  part of recording this decision, not left as a paper finding with no effect. */
    REVOKED
}
