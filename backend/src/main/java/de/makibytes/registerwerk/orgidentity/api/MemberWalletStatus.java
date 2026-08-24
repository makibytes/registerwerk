package de.makibytes.registerwerk.orgidentity.api;

/** Lifecycle of a member-wallet binding (OrgRegistry addMember/removeMember mirror). */
public enum MemberWalletStatus {
    /** addMember submitted, not yet confirmed. */
    PENDING,
    /** Binding confirmed onchain. */
    ACTIVE,
    /** Removal was requested; deny access until its receipt reaches finality. */
    REMOVAL_PENDING,
    /** removeMember succeeded at finality; the wallet may be re-bound later. */
    REMOVED,
    /** A finalized removeMember transaction reverted; access remains denied until retry. */
    REMOVAL_FAILED,
    /** The binding transaction failed onchain. */
    FAILED
}
