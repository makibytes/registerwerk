package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.ClaimsChallengeException;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AOP aspect enforcing {@code @RequiresStepUp} on regulator-grade endpoints: a recently proved
 * second factor, plus an optional second approver (4-eyes / Vieraugenprinzip).
 *
 * <p>How the second factor is proved depends on who issues session tokens — see
 * {@link StepUpMode}:
 *
 * <ul>
 *   <li><strong>{@code LOCAL_TOTP}</strong> — the caller sends, in place of their session token,
 *       a short-lived {@code acr=stepup} token that {@link StepUpTokenIssuer} minted after
 *       verifying a TOTP code. Rejection is a 403.</li>
 *   <li><strong>{@code ENTRA_AUTH_CONTEXT}</strong> — the access token must carry the required
 *       Conditional Access authentication context in {@code acrs}. Rejection is a
 *       <em>401 claims challenge</em>, so the SPA can silently re-acquire a qualifying token
 *       instead of logging the user out.</li>
 * </ul>
 *
 * <p>The 4-eyes check is identical in both modes: an {@code X-Dual-Control-Token} header
 * carrying a locally minted, action-scoped {@code acr=stepup} token from a <em>different</em>
 * enabled REGISTRY_ADMIN.
 */
@Aspect
@Component
class StepUpEnforcementAspect {

    private static final Logger log = LoggerFactory.getLogger(StepUpEnforcementAspect.class);
    private static final String DUAL_CONTROL_HEADER = "X-Dual-Control-Token";
    private static final String ACR_CLAIM = "acr";
    private static final String ACR_STEPUP = "stepup";
    private static final String ACRS_CLAIM = "acrs";
    private static final String AUTH_TIME_CLAIM = "auth_time";

    private final StepUpTokenValidator validator;
    private final StepUpPolicy policy;

    StepUpEnforcementAspect(StepUpTokenValidator validator, StepUpPolicy policy) {
        this.validator = validator;
        this.policy = policy;
    }

    @Around("@annotation(de.makibytes.registerwerk.stepup.api.RequiresStepUp) || " +
            "@within(de.makibytes.registerwerk.stepup.api.RequiresStepUp)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        RequiresStepUp stepUp = resolveAnnotation(pjp);
        if (stepUp == null) return pjp.proceed();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("Step-up auth requires a valid JWT.");
        }

        switch (policy.mode()) {
            case LOCAL_TOTP -> enforceLocalTotp(jwt, stepUp);
            case ENTRA_AUTH_CONTEXT -> enforceEntraAuthContext(jwt, stepUp);
        }

        // 4-eyes: second approver. Identical in both modes — a dual-control token is always
        // minted locally by StepUpTokenIssuer and verified against the local HS256 decoder, so
        // it does not depend on how the primary factor was proved.
        if (stepUp.requireSecondApprover()) {
            HttpServletRequest request = getCurrentRequest();
            String dualControlToken = request != null ? request.getHeader(DUAL_CONTROL_HEADER) : null;
            if (dualControlToken == null || dualControlToken.isBlank()) {
                throw new AccessDeniedException(
                        "This action requires dual control: provide a second REGISTRY_ADMIN " +
                        "step-up token in the " + DUAL_CONTROL_HEADER + " header.");
            }
            UUID approverId = validator.validateDualControlToken(dualControlToken, jwt.getSubject(), stepUp.reason());
            if (request != null) {
                request.setAttribute(
                        de.makibytes.registerwerk.stepup.api.StepUpAttributes.DUAL_CONTROL_APPROVER_ID, approverId);
            }
        }

        log.info("Step-up auth passed: mode={} sub={} action={} 4eyes={}",
                policy.mode(), jwt.getSubject(), stepUp.reason(), stepUp.requireSecondApprover());
        return pjp.proceed();
    }

    /**
     * Local mode: the caller replaces their session token with a short-lived {@code acr=stepup}
     * token minted by {@link StepUpTokenIssuer} after TOTP verification.
     */
    private void enforceLocalTotp(Jwt jwt, RequiresStepUp stepUp) {
        String acr = jwt.getClaimAsString(ACR_CLAIM);
        if (!ACR_STEPUP.equals(acr)) {
            log.warn("Step-up required but acr='{}' on sub={} for action={}",
                    acr, jwt.getSubject(), stepUp.reason());
            throw new AccessDeniedException(
                    "This action requires step-up authentication (acr=stepup). " +
                    "Complete MFA step-up at /api/v1/auth/step-up first.");
        }

        Instant iat = jwt.getIssuedAt();
        int maxAgeMinutes = stepUp.maxAgeMinutes();
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
    private void enforceEntraAuthContext(Jwt jwt, RequiresStepUp stepUp) {
        String required = policy.authContextIdFor(stepUp.reason());

        List<String> acrs = jwt.getClaimAsStringList(ACRS_CLAIM);
        if (acrs == null || !acrs.contains(required)) {
            log.info("Step-up challenge: sub={} action={} required={} present={}",
                    jwt.getSubject(), stepUp.reason(), required, acrs);
            throw new ClaimsChallengeException(required, policy.authorizationUri(), stepUp.reason());
        }

        Instant authTime = authTimeOf(jwt);
        if (authTime == null
                || authTime.isBefore(Instant.now().minusSeconds(stepUp.maxAgeMinutes() * 60L))) {
            log.info("Step-up re-challenge (stale authentication): sub={} action={} authTime={}",
                    jwt.getSubject(), stepUp.reason(), authTime);
            throw new ClaimsChallengeException(required, policy.authorizationUri(), stepUp.reason());
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

    private static RequiresStepUp resolveAnnotation(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresStepUp ann = method.getAnnotation(RequiresStepUp.class);
        if (ann == null) {
            ann = pjp.getTarget().getClass().getAnnotation(RequiresStepUp.class);
        }
        return ann;
    }

    private static HttpServletRequest getCurrentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }
}
