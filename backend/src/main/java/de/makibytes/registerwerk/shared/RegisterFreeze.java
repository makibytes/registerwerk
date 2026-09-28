package de.makibytes.registerwerk.shared;

import java.util.UUID;

/**
 * T3-07: refusal point for register-mutating operations on an asset whose register is frozen for a
 * §§21/22 eWpG handover ({@code TRANSFER_PENDING}) or already handed to the successor
 * ({@code TRANSFERRED_OUT}). Works on the status <em>name</em> so that modules which must not
 * depend on {@code asset} (blockchain, erc3643 - {@code asset} depends on them) can share it;
 * {@code asset.api.RegisterFreezeGuard} is the typed front for asset-aware callers. Throws
 * {@link InvalidStateTransitionException} (HTTP 409).
 */
public final class RegisterFreeze {

    public static final String TRANSFER_PENDING = "TRANSFER_PENDING";
    public static final String TRANSFERRED_OUT = "TRANSFERRED_OUT";

    private RegisterFreeze() {}

    public static boolean isFrozen(String statusName) {
        return TRANSFER_PENDING.equals(statusName) || TRANSFERRED_OUT.equals(statusName);
    }

    public static void requireOpen(String statusName, UUID assetId, String operation) {
        if (isFrozen(statusName)) {
            throw new InvalidStateTransitionException(
                    operation + " refused: the register of asset " + assetId + " is "
                            + (TRANSFERRED_OUT.equals(statusName)
                            ? "transferred to a successor registrar (TRANSFERRED_OUT)"
                            : "frozen for a register handover (TRANSFER_PENDING)"));
        }
    }
}
