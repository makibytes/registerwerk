package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import de.makibytes.registerwerk.finality.api.GatedOperation;

import java.util.Set;

/**
 * The compiled-in bottom rung of the resolution chain — code, not a seed row, so a fresh
 * database, an integration test, and a failed migration all resolve identically (no "what if the
 * seed migration never ran" class of bug).
 */
final class FinalityPolicyDefaults {

    /**
     * Every {@link GatedOperation} that emits a document or export leaving the system boundary —
     * these clamp to {@link FinalityLevel#FINALIZED} regardless of profile or override, and are
     * the primary risk control before a boundary-crossing action runs. FINALIZED is a configured
     * trust guarantee, not a mathematical impossibility: a typed FINALITY_VIOLATION still
     * quarantines the chain and requires correction/escalation for outputs that already escaped.
     */
    private static final Set<GatedOperation> HARD_FLOOR = Set.of(
            GatedOperation.REGISTER_STATEMENT_ISSUE,
            GatedOperation.REGISTER_EXTRACT_EXPORT,
            GatedOperation.REGISTER_TRANSFER_COMPLETE,
            GatedOperation.REGISTER_INSPECTION_FULFIL,
            GatedOperation.CORPORATE_ACTION_CONFIRMATION_EXPORT,
            GatedOperation.TAX_CERTIFICATE_ISSUE,
            GatedOperation.TRADE_CONFIRMATION_EXPORT,
            GatedOperation.REGULATORY_EXPORT);

    private FinalityPolicyDefaults() {}

    static boolean hasHardFloor(GatedOperation operation) {
        return HARD_FLOOR.contains(operation);
    }

    /**
     * The profile's nominal level for {@code operation}, before hard-floor clamping — callers
     * needing the final, clamped answer should go through {@link #clamp} too (see
     * {@link FinalityPolicyResolverImpl}, which always does both).
     *
     * <p>{@link FinalityPolicyProfile#FAST}: {@link FinalityLevel#SAFE} for every non-floored
     * operation. {@link FinalityPolicyProfile#BALANCED}/{@link FinalityPolicyProfile#CONSERVATIVE}:
     * {@link FinalityLevel#FINALIZED} for everything — see {@code FinalityPolicyProfile}'s javadoc
     * for why those two currently resolve identically.
     */
    static FinalityLevel nominalLevel(FinalityPolicyProfile profile, GatedOperation operation) {
        if (hasHardFloor(operation)) {
            return FinalityLevel.FINALIZED;
        }
        return profile == FinalityPolicyProfile.FAST ? FinalityLevel.SAFE : FinalityLevel.FINALIZED;
    }

    /** Clamps a resolved level up to FINALIZED if {@code operation} has a hard floor — the last
     *  step applied no matter which rung of the resolution chain produced {@code resolved}
     *  (including an operator override, which must not be able to lower a floored operation). */
    static FinalityLevel clamp(GatedOperation operation, FinalityLevel resolved) {
        if (hasHardFloor(operation) && resolved != FinalityLevel.FINALIZED) {
            return FinalityLevel.FINALIZED;
        }
        return resolved;
    }
}
