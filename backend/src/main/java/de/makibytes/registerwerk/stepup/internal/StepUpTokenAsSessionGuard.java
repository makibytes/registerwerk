package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpBearerAccepted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A locally minted {@code acr=stepup} token is signed with the same key and issuer as a session
 * token, so presented as a plain {@code Authorization: Bearer} it would authenticate on any
 * endpoint (6-01, panel finding). In LOCAL_TOTP mode it is, by design, the bearer of exactly the
 * {@link RequiresStepUp}-gated calls — so it is accepted there and nowhere else (403 elsewhere).
 * Lives here, not in {@code auth}, because the annotation belongs to {@code stepup}.
 *
 * <p>A dual-control <em>approver</em> token (marked {@code use=dual_control}) is never accepted, not even
 * on a {@link RequiresStepUp} endpoint: it is the second approver's single-use approval, valid only in the
 * {@code X-Dual-Control-Token} header, and as a Bearer it would run the request as that approver (C1).
 * {@code UserSessionGuardFilter} refuses it first; this is the second line for any path that reaches the
 * handler without that filter.
 */
@Configuration
class StepUpTokenAsSessionGuard implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws java.io.IOException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)
                        || !JwtMintingService.LOCAL_ISSUER.equals(jwt.getClaimAsString("iss"))) {
                    return true;
                }
                if (JwtMintingService.isDualControlApproverToken(jwt)) {
                    return reject(response, "A dual-control approver token is not a session token");
                }
                if (!"stepup".equals(jwt.getClaimAsString("acr")) || !(handler instanceof HandlerMethod method)) {
                    return true;
                }
                boolean gated = method.hasMethodAnnotation(RequiresStepUp.class)
                        || method.hasMethodAnnotation(StepUpBearerAccepted.class)
                        || method.getBeanType().isAnnotationPresent(RequiresStepUp.class);
                if (gated) {
                    return true;
                }
                return reject(response, "A step-up token is not a session token");
            }

            private boolean reject(HttpServletResponse response, String message) throws java.io.IOException {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":403,\"message\":\"" + message + "\"}");
                return false;
            }
        }).addPathPatterns("/api/**");
    }
}
