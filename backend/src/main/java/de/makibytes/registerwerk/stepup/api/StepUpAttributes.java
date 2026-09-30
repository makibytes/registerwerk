package de.makibytes.registerwerk.stepup.api;

/**
 * Request-attribute names {@code StepUpEnforcementAspect} populates for controllers to read via
 * {@code @RequestAttribute}, instead of every {@code @RequiresStepUp(requireSecondApprover =
 * true)} controller independently re-decoding the {@code X-Dual-Control-Token} JWT to learn who
 * the validated second approver was.
 */
public final class StepUpAttributes {

    private StepUpAttributes() {}

    /** {@code UUID} of the validated dual-control approver; present only when {@code
     * requireSecondApprover = true} and validation succeeded. */
    public static final String DUAL_CONTROL_APPROVER_ID = "stepup.dualControlApproverId";

    /** {@code UUID} correlating the {@code DUAL_CONTROL_APPROVED} audit entry with the request;
     * set by the aspect (after argument resolution — read it from the request, not a parameter). */
    public static final String DUAL_CONTROL_REQUEST_ID = "stepup.dualControlRequestId";
}
