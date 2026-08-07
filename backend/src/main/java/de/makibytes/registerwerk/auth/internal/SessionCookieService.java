package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Builds/reads the httpOnly session cookies that replaced returning the bearer token in the
 * login response body — a token sitting in {@code localStorage} is readable by any script that
 * runs on the page, including an XSS payload from a compromised dependency; a token an httpOnly
 * cookie carries is not (ledger finding: "Frontend JWTs are stored in localStorage").
 *
 * <p>Two cookies:
 * <ul>
 *   <li>{@code rw_session} — the active bearer token (session or impersonation).</li>
 *   <li>{@code rw_admin_session} — set only while impersonating, holding the REGISTRY_ADMIN's
 *       own token so {@code POST /api/v1/auth/exit-impersonation} can restore it. Frontend
 *       {@code /select-company} flow depends on the admin still being authenticated as
 *       themselves after leaving an impersonated session.</li>
 * </ul>
 *
 * {@code Secure} is tied to {@code REGISTERWERK_PRODUCTION_MODE} (same switch every other
 * production-only gate in this codebase uses) rather than unconditionally true: a
 * {@code Secure} cookie is never sent back by the browser over plain HTTP, which is exactly
 * how local/dev/docker-compose serves both frontends.
 */
@Component
public class SessionCookieService {

    public static final String SESSION_COOKIE = "rw_session";
    public static final String ADMIN_SESSION_COOKIE = "rw_admin_session";

    private final RegisterwerkAuthProperties authProps;
    private final boolean secure;

    public SessionCookieService(
            RegisterwerkAuthProperties authProps,
            @Value("${REGISTERWERK_PRODUCTION_MODE:false}") boolean productionMode) {
        this.authProps = authProps;
        this.secure = productionMode;
    }

    public ResponseCookie sessionCookie(String token) {
        return build(SESSION_COOKIE, token, authProps.getTokenTtlSeconds());
    }

    public ResponseCookie adminSessionCookie(String token) {
        return build(ADMIN_SESSION_COOKIE, token, authProps.getTokenTtlSeconds());
    }

    public ResponseCookie clearSessionCookie() {
        return build(SESSION_COOKIE, "", 0);
    }

    public ResponseCookie clearAdminSessionCookie() {
        return build(ADMIN_SESSION_COOKIE, "", 0);
    }

    public Optional<String> readSessionCookie(HttpServletRequest request) {
        return readCookie(request, SESSION_COOKIE);
    }

    public Optional<String> readAdminSessionCookie(HttpServletRequest request) {
        return readCookie(request, ADMIN_SESSION_COOKIE);
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private ResponseCookie build(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
