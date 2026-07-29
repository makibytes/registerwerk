package de.makibytes.registerwerk.stepup.internal;

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
import java.util.UUID;

/**
 * AOP aspect enforcing @RequiresStepUp: verifies fresh step-up token and optional
 * second-approver token (4-eyes / Vieraugenprinzip) on regulator-grade endpoints.
 *
 * Token requirements:
 *   1. Principal JWT must carry claim "acr" = "stepup"
 *   2. JWT iat must be within maxAgeMinutes of now
 *   3. If requireSecondApprover=true: X-Dual-Control-Token header must carry a
 *      valid REGISTRY_ADMIN JWT with "acr"="stepup" AND a different sub than the caller
 *
 * Wire-up: add TOTP/WebAuthn step-up endpoint that mints a short-lived JWT with acr=stepup.
 * Until WebAuthn is implemented, the endpoint at /api/v1/auth/step-up accepts TOTP codes.
 */
@Aspect
@Component
class StepUpEnforcementAspect {

    private static final Logger log = LoggerFactory.getLogger(StepUpEnforcementAspect.class);
    private static final String DUAL_CONTROL_HEADER = "X-Dual-Control-Token";
    private static final String ACR_CLAIM = "acr";
    private static final String ACR_STEPUP = "stepup";

    private final StepUpTokenValidator validator;

    StepUpEnforcementAspect(StepUpTokenValidator validator) {
        this.validator = validator;
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

        // 1. Check acr claim
        String acr = jwt.getClaimAsString(ACR_CLAIM);
        if (!ACR_STEPUP.equals(acr)) {
            log.warn("Step-up required but acr='{}' on sub={} for action={}",
                    acr, jwt.getSubject(), stepUp.reason());
            throw new AccessDeniedException(
                    "This action requires step-up authentication (acr=stepup). " +
                    "Complete MFA step-up at /api/v1/auth/step-up first.");
        }

        // 2. Check token freshness
        Instant iat = jwt.getIssuedAt();
        int maxAgeMinutes = stepUp.maxAgeMinutes();
        if (iat == null || iat.isBefore(Instant.now().minusSeconds(maxAgeMinutes * 60L))) {
            throw new AccessDeniedException(
                    "Step-up token expired. Re-authenticate at /api/v1/auth/step-up (max age: "
                    + maxAgeMinutes + " min).");
        }

        // 3. 4-eyes: second approver
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

        log.info("Step-up auth passed: sub={} action={} 4eyes={}",
                jwt.getSubject(), stepUp.reason(), stepUp.requireSecondApprover());
        return pjp.proceed();
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
