package de.makibytes.registerwerk.orgidentity.api;

/** Lifecycle of a member-wallet binding (OrgRegistry addMember/removeMember mirror). */
public enum MemberWalletStatus {
    /** addMember submitted, not yet confirmed. */
    PENDING,
    /** Binding confirmed onchain. */
    ACTIVE,
    /** removeMember executed; the wallet may be re-bound later. */
    REMOVED,
    /** The binding transaction failed onchain. */
    FAILED
}
