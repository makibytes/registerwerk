package de.makibytes.registerwerk.stepup.internal;

/**
 * How {@code @RequiresStepUp} is satisfied, which depends entirely on who issues session tokens.
 */
enum StepUpMode {

    /**
     * Registerwerk verifies a TOTP code itself and mints a short-lived {@code acr=stepup} token
     * that the caller sends in place of their session token. Used whenever sign-in is local
     * ({@code ENTRA_ENABLED=false}) — including the operator portal, which keeps this mode
     * permanently.
     */
    LOCAL_TOTP,

    /**
     * Entra performs the second factor under a Conditional Access authentication context, and
     * proves it with an {@code acrs} claim on the access token. Registerwerk verifies the claim
     * and, when it is absent, replies with an OAuth2 claims challenge so the SPA can re-acquire
     * a token that carries it.
     *
     * <p>The advantage over LOCAL_TOTP is that the strength of the second factor becomes a
     * Conditional Access policy decision — an operator can demand phishing-resistant MFA for
     * forced transfers without any code change here.
     */
    ENTRA_AUTH_CONTEXT
}
