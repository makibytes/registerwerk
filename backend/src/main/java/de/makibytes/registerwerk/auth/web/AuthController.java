package de.makibytes.registerwerk.auth.web;

import de.makibytes.registerwerk.auth.api.EntityDisplayNameResolver;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.internal.AuthService;
import de.makibytes.registerwerk.auth.internal.AuthService.LoginResult;
import de.makibytes.registerwerk.auth.internal.SessionCookieService;
import de.makibytes.registerwerk.auth.web.dto.ImpersonateExchangeRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookieService cookies;
    private final RegisterwerkAuthProperties authProperties;
    private final JwtDecoder jwtDecoder;
    private final EntityDisplayNameResolver entityDisplayNameResolver;

    public AuthController(
            AuthService authService,
            SessionCookieService cookies,
            RegisterwerkAuthProperties authProperties,
            @Qualifier("jwtDecoder") JwtDecoder jwtDecoder,
            EntityDisplayNameResolver entityDisplayNameResolver) {
        this.authService = authService;
        this.cookies = cookies;
        this.authProperties = authProperties;
        this.jwtDecoder = jwtDecoder;
        this.entityDisplayNameResolver = entityDisplayNameResolver;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResult result = authService.login(req.email(), req.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.sessionCookie(result.token()).toString())
                // A successful fresh login must never inherit an admin session left by an
                // interrupted impersonation flow; otherwise a later exit could restore the
                // wrong identity.
                .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                .body(toResponse(result));
    }

    /**
     * Unconditional — clearing cookies that may or may not exist is always safe, and a client
     * whose session already expired still needs to be able to drop stale cookies.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clearSessionCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString())
                .build();
    }

    /**
     * Exchanges a freshly minted impersonation token (from {@code AdminImpersonationController},
     * carried to this fresh customer-app tab in the handoff URL fragment) for a session cookie —
     * the customer app's {@code /admin/handoff} route has no existing session to attach an
     * {@code Authorization} header to, so this has to be the one endpoint that accepts a raw
     * token value in the body. Public, like login, but the "credential" being proven is
     * possession of an unguessable, freshly-signed, non-expired token rather than a password —
     * the same reasoning that keeps step-up/dual-control endpoints CSRF-exempt applies here too.
     *
     * <p>If the caller's browser already carries an {@code rw_session} cookie (rare — the
     * handoff tab is normally fresh — but possible if the same tab was already signed in), that
     * session is stashed in {@code rw_admin_session} so {@code POST /api/v1/auth/exit-
     * impersonation} can restore it; {@code /select-company} depends on the admin still being
     * authenticated as themselves after leaving an impersonated session.
     */
    @PostMapping("/impersonate")
    public ResponseEntity<LoginResponse> impersonate(
            @Valid @RequestBody ImpersonateExchangeRequest req, HttpServletRequest request) {
        if (authProperties.isEntraEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(req.token());
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID entityId;
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            entityId = UUID.fromString(jwt.getClaimAsString("entityId"));
        } catch (IllegalArgumentException | NullPointerException e) {
            // A signed JWT with an `imp` marker but no well-formed target is not a valid
            // handoff token. Return a normal authorization failure instead of leaking a 500.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        Optional<String> stashedAdminSession = cookies.readAdminSessionCookie(request);
        boolean hasRestorableAdminSession = stashedAdminSession
                .filter(this::isRestorableAdminSession)
                .isPresent();
        if (stashedAdminSession.isPresent() && !hasRestorableAdminSession) {
            response.header(HttpHeaders.SET_COOKIE, cookies.clearAdminSessionCookie().toString());
        }
        if (!hasRestorableAdminSession) {
            cookies.readSessionCookie(request)
                    .filter(this::isRestorableAdminSession)
                    .ifPresent(existing -> response.header(
                        HttpHeaders.SET_COOKIE, cookies.adminSessionCookie(existing).toString()));
        }
        response.header(HttpHeaders.SET_COOKIE, cookies.sessionCookie(req.token()).toString());

        String entityIdClaim = entityId.toString();
        String entityName = entityDisplayNameResolver.resolveName(entityId);

        return response.body(new LoginResponse(
                jwt.getSubject(),
                jwt.getClaimAsStringList("roles"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                entityIdClaim,
                entityName,
                true,
                jwt.getExpiresAt() != null ? jwt.getExpiresAt().getEpochSecond() : Instant.now().getEpochSecond()
        ));
    }

    private LoginResponse toResponse(LoginResult result) {
        return new LoginResponse(
                result.userId().toString(),
                result.roles(),
                result.email(),
                result.name(),
                result.entityId() != null ? result.entityId().toString() : null,
                null,
                false,
                Instant.now().plusSeconds(result.ttlSeconds()).getEpochSecond()
        );
    }

    /** Only a locally authenticated, non-impersonating registry administrator can be restored. */
    private boolean isRestorableAdminSession(String token) {
        try {
            Jwt session = jwtDecoder.decode(token);
            List<String> roles = session.getClaimAsStringList("roles");
            return !Boolean.TRUE.equals(session.getClaimAsBoolean("imp"))
                    && roles != null
                    && roles.contains("REGISTRY_ADMIN");
        } catch (JwtException e) {
            return false;
        }
    }
}
