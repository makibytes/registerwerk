package de.makibytes.registerwerk.finality.api;

import de.makibytes.registerwerk.shared.ComplianceGateException;

/**
 * Thrown by {@link FinalityGate#require} on a {@link FinalityDecision.Blocked} verdict. A subtype
 * of {@link ComplianceGateException} (itself an {@code IllegalStateException}), so it maps to 409
 * via {@code shared.web.GlobalExceptionHandler.handleComplianceGate} with zero new plumbing — and
 * is recorded as a rejected action in the audit log the same way every other compliance-gate
 * rejection already is.
 *
 * <p>409, not 403: "not in a state where this is possible <em>yet</em>" is what is true, and it is
 * the distinction a UI needs to render "available in ~Ns" instead of "access denied." A richer
 * response body — carrying {@link #decision()}'s required/current level and reason, not just a
 * message — is served by {@code finality.web}'s own {@code @RestControllerAdvice}, registered
 * separately from {@code shared.web.GlobalExceptionHandler} so {@code shared} (the dependency-free
 * foundation kernel) never needs to import {@code finality} — Spring dispatches to whichever
 * registered handler matches the most specific exception type, so the finality-specific advice
 * wins over the generic {@code ComplianceGateException} one without any explicit ordering.
 */
public class FinalityNotReachedException extends ComplianceGateException {

    private final FinalityDecision.Blocked decision;

    public FinalityNotReachedException(FinalityDecision.Blocked decision) {
        super(decision.explanation());
        this.decision = decision;
    }

    public FinalityDecision.Blocked decision() { return decision; }
}
