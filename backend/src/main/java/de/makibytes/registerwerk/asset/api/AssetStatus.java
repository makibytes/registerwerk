package de.makibytes.registerwerk.asset.api;

public enum AssetStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    ISSUED,
    SUSPENDED,
    REDEEMED,
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
