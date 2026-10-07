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

    /** Entity-task kind (on the issuer) for a redemption that waits for the OPERATOR, not for the issuer (9A-04R). */
    static final String TASK_REDEMPTION_OPERATOR_PENDING = "BOND_REDEMPTION_OPERATOR_PENDING";
    /** Entity-task kind (on the issuer) for a coupon that waits for the OPERATOR (9A-04R). */
    static final String TASK_COUPON_OPERATOR_PENDING = "COUPON_OPERATOR_PENDING";
    /** Entity-task kinds raised when the system moves a bond to OVERDUE / DEFAULTED (9A-04R). */
    static final String TASK_BOND_OVERDUE = "BOND_OVERDUE";
    static final String TASK_BOND_DEFAULT_REVIEW = "BOND_DEFAULT_REVIEW";
    static final String TASK_COUPON_OVERDUE = "COUPON_OVERDUE";
    static final String TASK_COUPON_MISSED = "COUPON_MISSED";

    /** Who a past-due action is waiting for. Only {@link #ISSUER} counts toward OVERDUE / DEFAULTED / MISSED. */
    enum Side { ISSUER, OPERATOR, REGISTRY }

    /** The side an action is waiting on, and (for OPERATOR / REGISTRY) why; {@code cause} is null for ISSUER. */
    record Responsibility(Side side, String cause) {
        boolean countsAsIssuerNonPayment() {
            return side == Side.ISSUER;
        }
    }

    private CorporateActionBlocks() {}

    /**
     * REGISTRY = {@link #systemBlockCause}; OPERATOR = the issuer has attested but the operator has not confirmed
     * ({@code OPERATOR_CONFIRMATION_MISSING}), or both signed / the settlement was dispatched and is still pending
     * ({@code SETTLEMENT_NOT_DISPATCHED}, manual-settle lag); ISSUER = everything else, i.e. the issuer's attestation
     * is outstanding (or the action is still before COMPUTED). Operator slowness must never default a bond (9A-04R).
     */
    static Responsibility responsibleSide(CorporateAction ca, boolean registerFrozen) {
        Optional<String> registry = systemBlockCause(ca, registerFrozen);
        if (registry.isPresent()) {
            return new Responsibility(Side.REGISTRY, registry.get());
        }
        if (ca.getStatus() == CorporateAction.Status.AWAITING_SETTLEMENT) {
            return new Responsibility(Side.OPERATOR,
                    "SETTLEMENT_NOT_DISPATCHED: the settlement is awaiting the operator / chain");
        }
        if (ca.getStatus() == CorporateAction.Status.COMPUTED && ca.getIssuerAttestedAt() != null) {
            return ca.getDualControlApproverId() == null
                    ? new Responsibility(Side.OPERATOR,
                            "OPERATOR_CONFIRMATION_MISSING: the issuer attested, the operator has not confirmed yet")
                    : new Responsibility(Side.OPERATOR,
                            "SETTLEMENT_NOT_DISPATCHED: both parties signed, the settlement is not dispatched yet");
        }
        return new Responsibility(Side.ISSUER, null);
    }

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
