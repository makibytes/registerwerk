package de.makibytes.registerwerk.auth.web;

import de.makibytes.registerwerk.auth.api.EntityDisplayNameResolver;
import de.makibytes.registerwerk.auth.internal.SessionCookieService;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Authenticated session endpoints — the httpOnly {@code rw_session} cookie
 * ({@link SessionCookieService}) is invisible to JS by design, so both frontends'
 * {@code AuthService} rehydrate "who is signed in and with what roles" from here on app
 * bootstrap and after login, instead of decoding a token they can no longer read.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

    private final SessionCookieService cookies;
    private final JwtDecoder jwtDecoder;
    private final EntityDisplayNameResolver entityDisplayNameResolver;

    public SessionController(
            SessionCookieService cookies,
            @Qualifier("jwtDecoder") JwtDecoder jwtDecoder,
            EntityDisplayNameResolver entityDisplayNameResolver) {
        this.cookies = cookies;
        this.jwtDecoder = jwtDecoder;
        this.entityDisplayNameResolver = entityDisplayNameResolver;
    }

    @GetMapping("/session")
    public ResponseEntity<LoginResponse> session(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        boolean impersonating = SecurityUtils.isImpersonatingAdmin(auth);
        long expiresAt = (auth.getPrincipal() instanceof Jwt jwt && jwt.getExpiresAt() != null)
                ? jwt.getExpiresAt().getEpochSecond()
                : Instant.now().getEpochSecond();
        String entityName = (impersonating && entityId != null)
                ? entityDisplayNameResolver.resolveName(entityId)
                : null;
        return ResponseEntity.ok(new LoginResponse(
                SecurityUtils.extractUserId(auth).toString(),
                SecurityUtils.extractRoles(auth),
                SecurityUtils.extractEmail(auth),
                SecurityUtils.extractDisplayName(auth),
                entityId != null ? entityId.toString() : null,
                entityName,
                impersonating,
                expiresAt
        ));
    }

    /**
     * Restores the REGISTRY_ADMIN's own session from {@code rw_admin_session} (stashed by
     * {@code POST /api/v1/public/auth/impersonate} when one existed) — the customer app's
     * {@code /select-company} flow depends on the admin still being authenticated as themselves
     * after leaving an impersonated session, not merely logged out. Falls back to a plain
     * logout when there is nothing to restore (the common case: a fresh handoff tab never had
     * one) or the stashed token has since expired.
     */
    @PostMapping("/exit-impersonation")
    public ResponseEntity<Void> exitImpersonation(Authentication auth, HttpServletRequest request) {
        // A stale client action must not turn a regular admin session into a logout. Clear an
        // orphaned restore cookie, but leave the active, non-impersonating session untouched.
        if (!SecurityUtils.isImpersonatingAdmin(auth)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                    .build();
        }

        var stashed = cookies.readAdminSessionCookie(request);
        if (stashed.isEmpty()) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, cookies.clearSessionCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                    .build();
        }

        Jwt adminSession;
        try {
            adminSession = jwtDecoder.decode(stashed.get()); // throws if expired/malformed
        } catch (JwtException e) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, cookies.clearSessionCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                    .build();
        }
        if (!isRestorableAdminSession(adminSession)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, cookies.clearSessionCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                    .build();
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.sessionCookie(stashed.get()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                .build();
    }

    private boolean isRestorableAdminSession(Jwt session) {
        var roles = session.getClaimAsStringList("roles");
        return !Boolean.TRUE.equals(session.getClaimAsBoolean("imp"))
                && roles != null
                && roles.contains("REGISTRY_ADMIN");
    }
}
