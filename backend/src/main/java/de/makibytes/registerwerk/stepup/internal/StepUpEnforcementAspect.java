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

    private final StepUpEnforcer enforcer;
    private final DualControlService dualControl;

    StepUpEnforcementAspect(StepUpEnforcer enforcer, DualControlService dualControl) {
        this.enforcer = enforcer;
        this.dualControl = dualControl;
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

        enforcer.enforce(jwt, stepUp.reason(), stepUp.maxAgeMinutes());

        // 4-eyes: second approver. Identical in both modes — a dual-control token is always
        // minted locally by StepUpTokenIssuer and verified against the local HS256 decoder, so
        // it does not depend on how the primary factor was proved. The approval is bound to this
        // exact request and consumed here (K3, 6-08).
        if (stepUp.requireSecondApprover()) {
            dualControl.approveAndConsume(auth, jwt, stepUp.reason(), getCurrentRequest());
        }

        log.info("Step-up auth passed: mode={} sub={} action={} 4eyes={}",
                enforcer.mode(), jwt.getSubject(), stepUp.reason(), stepUp.requireSecondApprover());
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
