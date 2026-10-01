package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.internal.SessionStateService.ImpersonationState;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the impersonation mode (6-31). READ_ONLY sessions may only read (GET/HEAD/OPTIONS) and
 * end themselves; ACT_ON_BEHALF sessions additionally lose a configurable deny-list of customer
 * attestation and account/identity-administration endpoints. Runs after
 * {@link UserSessionGuardFilter}, so the session row is known to be live.
 */
class ImpersonationGuardFilter extends OncePerRequestFilter {

    static final String CODE_READ_ONLY = "IMPERSONATION_READ_ONLY";
    static final String CODE_DENIED = "IMPERSONATION_ACTION_DENIED";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final List<String> ALWAYS_ALLOWED =
            List.of("/api/v1/auth/exit-impersonation", "/api/v1/public/auth/logout");

    private final SessionStateService state;
    private final List<String> denyPatterns;
    private final AntPathMatcher matcher = new AntPathMatcher();

    ImpersonationGuardFilter(SessionStateService state, List<String> denyPatterns) {
        this.state = state;
        this.denyPatterns = denyPatterns == null ? List.of() : List.copyOf(denyPatterns);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)
                || !Boolean.TRUE.equals(jwtAuth.getToken().getClaimAsBoolean("imp"))) {
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (READ_METHODS.contains(method) || ALWAYS_ALLOWED.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = jwtAuth.getToken();
        Optional<ImpersonationState> imp = parse(jwt.getId()).flatMap(state::impersonation);
        ImpersonationMode mode = imp.map(ImpersonationState::mode).orElse(ImpersonationMode.READ_ONLY);
        if (mode == ImpersonationMode.READ_ONLY) {
            reject(response, CODE_READ_ONLY, "This is a read-only support session; changes are not permitted.");
            return;
        }
        if (denied(method, path)) {
            reject(response, CODE_DENIED,
                    "This action must be performed by the customer and cannot be done on their behalf.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean denied(String method, String path) {
        for (String rule : denyPatterns) {
            int space = rule.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String m = rule.substring(0, space);
            if (("*".equals(m) || m.equalsIgnoreCase(method)) && matcher.match(rule.substring(space + 1).trim(), path)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<UUID> parse(String v) {
        try {
            return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":403,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
