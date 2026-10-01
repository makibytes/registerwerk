package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.internal.SessionStateService.ImpersonationState;
import de.makibytes.registerwerk.auth.internal.SessionStateService.UserState;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request account check for every authenticated JWT (HS256 session/step-up/impersonation and
 * Entra/OIDC alike), 6-01. A valid signature alone is not enough: the account must still exist and
 * be enabled, the token must not predate {@code app_user.tokens_valid_after} (set by every
 * disable/role/entity/password change and explicit revocation), its {@code jti} must not be in
 * {@code session_revocation} (logout), the user's entity must not be CLOSED/DISSOLVED, and an
 * impersonation token needs a live {@code impersonation_session} whose actor is still an enabled
 * REGISTRY_ADMIN.
 *
 * <p>Revocation is by {@code iat} versus a per-user timestamp and by {@code jti}, deliberately not
 * by comparing mutable role claims. The {@code tokens_valid_after} cut-off applies to locally
 * minted tokens only: an Entra token's roles and entity are re-read from the row on every request
 * ({@code EntraPrincipalNormalizationFilter}), so it carries nothing stale, and the IdP may
 * legitimately keep re-serving a cached token whose {@code iat} is old.
 *
 * <p>Cache staleness (15 s TTL across replicas; in-process changes evict immediately) is the
 * revocation SLA.
 */
class UserSessionGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserSessionGuardFilter.class);

    private final SessionStateService state;
    private final MeterRegistry meters;
    private final boolean rejectUnknownUsers;

    UserSessionGuardFilter(SessionStateService state, MeterRegistry meters, boolean rejectUnknownUsers) {
        this.state = state;
        this.meters = meters;
        this.rejectUnknownUsers = rejectUnknownUsers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        String reason = rejectionReason(jwtAuth.getToken());
        if (reason != null) {
            SecurityContextHolder.clearContext();
            meters.counter("registerwerk_session_rejections_total", "reason", reason).increment();
            log.info("Session rejected: reason={} sub={} path={}", reason, jwtAuth.getToken().getSubject(),
                    request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate",
                    "Bearer error=\"invalid_token\", error_description=\"session no longer valid\"");
            return;
        }
        chain.doFilter(request, response);
    }

    /** @return a short metric-safe reason, or null when the session is acceptable */
    String rejectionReason(Jwt jwt) {
        UUID userId = parse(jwt.getSubject());
        if (userId == null) {
            return rejectUnknownUsers ? "unknown_user" : null;
        }
        Optional<UserState> found = state.user(userId);
        if (found.isEmpty()) {
            return rejectUnknownUsers ? "unknown_user" : null;
        }
        UserState user = found.get();
        if (!user.enabled()) {
            return "disabled";
        }
        boolean local = JwtMintingService.LOCAL_ISSUER.equals(jwt.getClaimAsString("iss"));
        if (local) {
            Instant iat = jwt.getIssuedAt();
            if (user.tokensValidAfter() != null && (iat == null || iat.isBefore(user.tokensValidAfter()))) {
                return "revoked_after";
            }
            String jti = jwt.getId();
            if (jti != null && state.isRevoked(jti)) {
                return "revoked";
            }
        }
        if (user.entityTerminated()) {
            return "entity_closed";
        }
        if (local && Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            UUID sessionId = parse(jwt.getId());
            if (sessionId == null) {
                return "impersonation_invalid";
            }
            Optional<ImpersonationState> imp = state.impersonation(sessionId);
            if (imp.isEmpty() || !imp.get().active() || !userId.equals(imp.get().actorId())) {
                return "impersonation_ended";
            }
            if (!user.registryAdmin()) {
                return "impersonation_actor_demoted";
            }
            if (imp.get().targetTerminated()) {
                return "entity_closed";
            }
        }
        return null;
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
