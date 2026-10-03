package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;

import java.util.Optional;

/**
 * What counts as a REGISTRY-side block of a corporate action (Wave 0b H6): the record-date snapshot is refused,
 * the register is frozen / handed over, or the system itself holds the settlement back. Such an action must not be
 * presented as the issuer's non-payment (coupon OVERDUE / MISSED, bond OVERDUE / DEFAULTED); it stays blocked and an
 * operator task is raised. An action that merely waits for the issuer's attestation or the operator's confirmation is
 * NOT blocked in this sense.
 */
final class CorporateActionBlocks {

    /** Entity-task kind for a holder whose entitlement was held at payout. */
    static final String TASK_PAYOUT_HELD = "CORPORATE_ACTION_PAYOUT_HELD";
    /** Entity-task kind (on the issuer) for a bond redemption the registry side holds back. */
    static final String TASK_REDEMPTION_BLOCKED = "BOND_REDEMPTION_BLOCKED";

    private CorporateActionBlocks() {}

    static Optional<String> systemBlockCause(CorporateAction ca, boolean registerFrozen) {
        if (registerFrozen) {
            return Optional.of("the register is frozen or handed over (eWpG §§21/22)");
        }
        if (ca.getStatus() == CorporateAction.Status.SNAPSHOT_BLOCKED) {
            return Optional.of("the record-date snapshot is blocked: " + ca.getSnapshotBlockedReason());
        }
        if (ca.getSettlementHoldReason() != null && !ca.getSettlementHoldReason().isBlank()) {
            return Optional.of("settlement is held by the system: " + ca.getSettlementHoldReason());
        }
        return Optional.empty();
    }
}
