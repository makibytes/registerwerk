package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * T3-23: exposes the validated second approver ({@link StepUpAttributes#DUAL_CONTROL_APPROVER_ID})
 * <em>before</em> Spring resolves the controller's arguments.
 *
 * <p>{@code StepUpEnforcementAspect} runs around the controller method, i.e. after argument
 * resolution, so a controller parameter {@code @RequestAttribute(DUAL_CONTROL_APPROVER_ID,
 * required = false) UUID approverId} was always {@code null} and the second approver never reached
 * the audit trail. This interceptor runs earlier ({@code preHandle}), validates the
 * {@code X-Dual-Control-Token} exactly as the aspect does and sets the attribute. It never rejects:
 * a missing or invalid token is left for the aspect to refuse with its usual 403, so the
 * enforcement decision stays in one place.
 */
@Component
class DualControlApproverInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DualControlApproverInterceptor.class);
    static final String DUAL_CONTROL_HEADER = "X-Dual-Control-Token";

    private final StepUpTokenValidator validator;

    DualControlApproverInterceptor(StepUpTokenValidator validator) {
        this.validator = validator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresStepUp stepUp = method.getMethodAnnotation(RequiresStepUp.class);
        if (stepUp == null) {
            stepUp = method.getBeanType().getAnnotation(RequiresStepUp.class);
        }
        if (stepUp == null || !stepUp.requireSecondApprover()) {
            return true;
        }
        String token = request.getHeader(DUAL_CONTROL_HEADER);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (token == null || token.isBlank() || auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return true; // the aspect rejects
        }
        try {
            UUID approverId = validator.validateDualControlToken(token, jwt.getSubject(), stepUp.reason());
            request.setAttribute(StepUpAttributes.DUAL_CONTROL_APPROVER_ID, approverId);
        } catch (RuntimeException e) {
            log.debug("Dual-control token not accepted in preHandle (aspect will reject): {}", e.getMessage());
        }
        return true;
    }
}
