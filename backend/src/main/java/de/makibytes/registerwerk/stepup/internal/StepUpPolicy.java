package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides how a step-up requirement is satisfied, and against which Conditional Access
 * authentication context.
 */
@Component
class StepUpPolicy {

    private static final Logger log = LoggerFactory.getLogger(StepUpPolicy.class);

    private final RegisterwerkAuthProperties authProperties;
    private final StepUpEntraProperties entraProperties;

    StepUpPolicy(RegisterwerkAuthProperties authProperties, StepUpEntraProperties entraProperties) {
        this.authProperties = authProperties;
        this.entraProperties = entraProperties;
    }

    StepUpMode mode() {
        return authProperties.isEntraEnabled() ? StepUpMode.ENTRA_AUTH_CONTEXT : StepUpMode.LOCAL_TOTP;
    }

    /**
     * The authentication context required for this action.
     *
     * @throws IllegalStateException when Entra mode is on but no context is configured. Failing
     *         closed is the only safe option: there is no sane default, and inventing one would
     *         mean protecting regulator-grade actions with a context that may not even be
     *         published to the app. {@code ProductionReadinessCheck} catches this at startup so
     *         it does not first surface as a runtime 500.
     */
    String authContextIdFor(String reason) {
        String override = entraProperties.getReasonOverrides().get(reason);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String fallback = entraProperties.getAuthContextId();
        if (fallback.isBlank()) {
            log.error("Step-up requested for action '{}' but no Conditional Access authentication context "
                    + "is configured. Set ENTRA_STEPUP_AUTH_CONTEXT_ID.", reason);
            throw new IllegalStateException(
                    "No Conditional Access authentication context is configured for step-up "
                    + "(registerwerk.auth.step-up.entra.auth-context-id).");
        }
        return fallback;
    }

    String authorizationUri() {
        return entraProperties.getAuthorizationUri();
    }
}
