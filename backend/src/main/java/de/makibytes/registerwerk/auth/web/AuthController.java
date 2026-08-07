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
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        cookies.readSessionCookie(request).ifPresent(existing ->
                response.header(HttpHeaders.SET_COOKIE, cookies.adminSessionCookie(existing).toString()));
        response.header(HttpHeaders.SET_COOKIE, cookies.sessionCookie(req.token()).toString());

        String entityIdClaim = jwt.getClaimAsString("entityId");
        String entityName = entityIdClaim != null
                ? entityDisplayNameResolver.resolveName(java.util.UUID.fromString(entityIdClaim))
                : null;

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
}
