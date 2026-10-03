package de.makibytes.registerwerk.asset.api;

public enum AssetStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    ISSUED,
    SUSPENDED,
    REDEEMED,
    /** Redemption started (Wave 0b C7): the burns it dispatched are not all final yet. The asset becomes REDEEMED only
     *  when every burn is confirmed (final receipt plus the indexed BURN transfer); a failed burn leaves it here, with
     *  an operator alert, until the redemption is resumed. Trading, subscriptions and mints are closed meanwhile. */
    REDEMPTION_PENDING,
    /** Register handover (eWpG §§21/22) exported to the successor but not yet completed (T3-07):
     *  the register is frozen - no trading, mint/burn/forced operations, corporate-action
     *  processing or holder edits - so the exported package stays valid until {@code complete}.
     *  Set at export, restored to the previous status on cancel. */
    TRANSFER_PENDING,
    /** Register handed over to a successor operator (eWpG §§21/22) — this registrar no longer
     *  administers the security; automated coupon/redemption jobs must not touch it. */
    TRANSFERRED_OUT;

    /** False once the register was handed to a successor: nothing may be issued or processed here. */
    public boolean isAdministeredHere() {
        return this != TRANSFERRED_OUT;
    }

    /** True while the register is frozen for (or after) a handover: state-changing register operations are refused. */
    public boolean isRegisterFrozen() {
        return this == TRANSFER_PENDING || this == TRANSFERRED_OUT;
    }
}
