package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.stepup.api.ClaimsChallengeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The caller's own step-up proof, shared by {@code StepUpEnforcementAspect} (annotation) and
 * {@code DualControlService} (programmatic gate) so both apply the identical rule per {@link StepUpMode}.
 */
@Component
class StepUpEnforcer {

    private static final Logger log = LoggerFactory.getLogger(StepUpEnforcer.class);
    private static final String ACR_CLAIM = "acr";
    private static final String ACR_STEPUP = "stepup";
    private static final String ACRS_CLAIM = "acrs";
    private static final String AUTH_TIME_CLAIM = "auth_time";

    private final StepUpPolicy policy;

    StepUpEnforcer(StepUpPolicy policy) {
        this.policy = policy;
    }

    StepUpMode mode() {
        return policy.mode();
    }

    void enforce(Jwt jwt, String reason, int maxAgeMinutes) {
        // C1: a second approver's token is that person's approval of one request, not the caller's own
        // proof. Accepting it here would run the request as the approver.
        if (JwtMintingService.isDualControlApproverToken(jwt)) {
            log.warn("Dual-control approver token presented as the caller's own step-up proof: sub={} action={}",
                    jwt.getSubject(), reason);
            throw new AccessDeniedException(
                    "A dual-control approver token cannot be used as the caller's own step-up proof. "
                    + "Complete MFA step-up at /api/v1/auth/step-up for your own session.");
        }
        switch (policy.mode()) {
            case LOCAL_TOTP -> enforceLocalTotp(jwt, reason, maxAgeMinutes);
            case ENTRA_AUTH_CONTEXT -> enforceEntraAuthContext(jwt, reason, maxAgeMinutes);
        }
    }

    /**
     * Local mode: the caller replaces their session token with a short-lived {@code acr=stepup}
     * token minted by {@link StepUpTokenIssuer} after TOTP verification.
     */
    private void enforceLocalTotp(Jwt jwt, String reason, int maxAgeMinutes) {
        String acr = jwt.getClaimAsString(ACR_CLAIM);
        if (!ACR_STEPUP.equals(acr)) {
            log.warn("Step-up required but acr='{}' on sub={} for action={}",
                    acr, jwt.getSubject(), reason);
            throw new AccessDeniedException(
                    "This action requires step-up authentication (acr=stepup). " +
                    "Complete MFA step-up at /api/v1/auth/step-up first.");
        }

        Instant iat = jwt.getIssuedAt();
        if (iat == null || iat.isBefore(Instant.now().minusSeconds(maxAgeMinutes * 60L))) {
            throw new AccessDeniedException(
                    "Step-up token expired. Re-authenticate at /api/v1/auth/step-up (max age: "
                    + maxAgeMinutes + " min).");
        }
    }

    /**
     * Entra mode: the access token must carry the required Conditional Access authentication
     * context in {@code acrs}. When it does not, reply with a claims challenge so the SPA can
     * re-acquire a token that does — the caller keeps their session either way.
     *
     * <p><strong>Freshness works differently here, on purpose.</strong> An Entra access token
     * lives 60–90 minutes and {@code acrs} persists for its whole lifetime, so applying
     * {@code maxAgeMinutes} to {@code iat} would force a full browser redirect on nearly every
     * protected call. The real freshness control is the Conditional Access policy attached to
     * the authentication context ("Sign-in frequency: Every time"); this check reads
     * {@code auth_time} — when the user actually authenticated — and acts as a backstop.
     *
     * <p>{@code auth_time} is an optional claim that has to be requested on the API app
     * registration. Absent it, we fall back to {@code iat}, which is weaker;
     * {@code EntraPrincipalNormalizationFilter} logs a warning the first time it sees that.
     */
    private void enforceEntraAuthContext(Jwt jwt, String reason, int maxAgeMinutes) {
        String required = policy.authContextIdFor(reason);

        List<String> acrs = jwt.getClaimAsStringList(ACRS_CLAIM);
        if (acrs == null || !acrs.contains(required)) {
            log.info("Step-up challenge: sub={} action={} required={} present={}",
                    jwt.getSubject(), reason, required, acrs);
            throw new ClaimsChallengeException(required, policy.authorizationUri(), reason);
        }

        Instant authTime = authTimeOf(jwt);
        if (authTime == null
                || authTime.isBefore(Instant.now().minusSeconds(maxAgeMinutes * 60L))) {
            log.info("Step-up re-challenge (stale authentication): sub={} action={} authTime={}",
                    jwt.getSubject(), reason, authTime);
            throw new ClaimsChallengeException(required, policy.authorizationUri(), reason);
        }
    }

    private static Instant authTimeOf(Jwt jwt) {
        Object authTime = jwt.getClaim(AUTH_TIME_CLAIM);
        if (authTime instanceof Instant instant) {
            return instant;
        }
        if (authTime instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        return jwt.getIssuedAt();
    }
}
