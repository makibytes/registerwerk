package de.makibytes.registerwerk.finality.api;

import de.makibytes.registerwerk.deployment.api.TokenStandard;

import java.util.UUID;

/**
 * The enforcement point for {@link FinalityPolicyService}'s resolved requirement. Deliberately
 * does <b>not</b> look up {@code currentLevel} itself — the caller (registerstatement,
 * registertransfer, etc.) already knows which {@code token_transfer} row or block its own
 * operation depends on, and having the gate query that directly would require {@code finality} to
 * import {@code indexer}/{@code blockchain}, which already depend on {@code finality} (chain-effect
 * journalling since P6) — the same import-direction constraint that keeps
 * {@link FinalityPolicyService#requiredLevel} taking {@code tokenStandard} as a parameter instead
 * of looking it up via {@code asset.api}.
 *
 * <p>Call this <b>inside the mutating transaction, immediately before the write</b> — never at
 * controller entry, never as an annotation — so the check and the write observe the same
 * snapshot. Scheduled jobs must use {@link #check} and skip-and-reattempt on {@link
 * FinalityDecision.Blocked}, never call {@link #require} (an uncaught {@link
 * FinalityNotReachedException} would fail the whole job run, not just this row).
 */
public interface FinalityGate {

    /** Never throws — inspect the returned {@link FinalityDecision} to decide what to do. */
    FinalityDecision check(GatedOperation operation, UUID assetId, TokenStandard tokenStandard, FinalityLevel currentLevel);

    /** {@link #check} plus: throws {@link FinalityNotReachedException} on {@link
     *  FinalityDecision.Blocked} instead of returning it — for the common case of a controller-
     *  triggered write where "blocked" and "reject the request" are the same thing. */
    void require(GatedOperation operation, UUID assetId, TokenStandard tokenStandard, FinalityLevel currentLevel);
}
