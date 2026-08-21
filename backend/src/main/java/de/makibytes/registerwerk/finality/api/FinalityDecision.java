package de.makibytes.registerwerk.finality.api;

import java.util.UUID;

/**
 * The result of a {@link FinalityGate} check. Sealed so every caller's switch stays exhaustive as
 * new decision shapes are added.
 */
public sealed interface FinalityDecision {

    /** {@code currentLevel} is carried through even on an allow, so a caller/UI can distinguish
     *  "allowed, comfortably FINALIZED" from "allowed, right at the required threshold" without a
     *  second lookup. */
    record Allowed(FinalityLevel currentLevel) implements FinalityDecision {}

    /**
     * @param reason why this is blocked — {@link Reason#ORPHANED} is distinct from {@link
     *               Reason#BELOW_REQUIRED} because it means the underlying on-chain event is gone,
     *               not merely still confirming; a UI renders these very differently ("did not go
     *               through" vs. "being confirmed, available in ~Ns")
     * @param explanation a technical-vocabulary message (PROVISIONAL/SAFE/FINALIZED/ORPHANED) —
     *                     role-based translation to plain trader/investor language happens at the
     *                     web/DTO layer that renders this, not here (keeps this record simple and
     *                     independent of who is asking)
     */
    record Blocked(GatedOperation operation, UUID assetId, FinalityLevel requiredLevel,
            FinalityLevel currentLevel, Reason reason, String explanation) implements FinalityDecision {

        public enum Reason { BELOW_REQUIRED, ORPHANED }
    }
}
