package de.makibytes.registerwerk.asset.api;

public enum AssetStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    ISSUED,
    SUSPENDED,
    REDEEMED,
    /** Register handed over to a successor operator (eWpG §§21/22) — this registrar no longer
     *  administers the security; automated coupon/redemption jobs must not touch it. */
    TRANSFERRED_OUT
}
