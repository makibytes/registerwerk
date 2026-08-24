package de.makibytes.registerwerk.auth.internal;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rewrites an Entra-issued token in the security context so that its {@code sub} is the
 * Registerwerk {@code app_user.id} rather than Entra's object id, and so that roles and entity
 * scope come from the account row rather than from whatever the token happened to claim.
 *
 * <p><strong>Why a filter rather than fixing the call sites.</strong> Roughly a hundred places
 * call {@code SecurityUtils.extractUserId(auth)} and treat the result as an {@code app_user.id}
 * — audit actor ids, step-up token issuance, dual-control self-approval checks, ownership
 * checks. In Entra mode every one of them was silently wrong, because the token's {@code sub}
 * is an Entra identifier that matches no row. Normalising once, here, corrects all of them
 * without touching any.
 *
 * <p><strong>No-op for locally minted tokens</strong>, identified by {@code iss}. Those already
 * carry the right {@code sub}, and re-deriving their claims would be pure risk.
 *
 * <p>Claims are <em>added</em>, never removed: {@code acrs}, {@code auth_time}, {@code xms_cc}
 * and the rest survive, because the step-up aspect reads them off this same token.
 */
class EntraPrincipalNormalizationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EntraPrincipalNormalizationFilter.class);

    private static final String AUTH_TIME_WARNED = "authTimeWarned";

    private final PrincipalResolver principalResolver;
    private volatile boolean authTimeAbsenceLogged = false;

    EntraPrincipalNormalizationFilter(PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Jwt token = jwtAuth.getToken();
        if (JwtMintingService.LOCAL_ISSUER.equals(token.getClaimAsString("iss"))) {
            chain.doFilter(request, response);
            return;
        }

        AppUser user;
        try {
            user = principalResolver.requireUser(auth);
        } catch (AccessDeniedException e) {
            // Leave the original authentication in place and let the normal authorisation
            // machinery produce the 403. Throwing from a filter would bypass the configured
            // AccessDeniedHandler and surface as a 500.
            log.warn("Could not resolve an account for Entra principal sub={}: {}",
                    token.getSubject(), e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        warnOnceIfAuthTimeMissing(token);

        Jwt normalized = Jwt.withTokenValue(token.getTokenValue())
                .headers(headers -> headers.putAll(token.getHeaders()))
                .claims(claims -> {
                    claims.putAll(token.getClaims());
                    claims.put("sub", user.getId().toString());
                    claims.put("entra_oid", token.getClaimAsString("oid"));
                    claims.put("entra_tid", token.getClaimAsString("tid"));
                    claims.put("roles", user.getRoles().stream().map(Enum::name).toList());
                    if (user.getEmail() != null) {
                        claims.put("email", user.getEmail());
                    }
                    if (user.getFullName() != null) {
                        claims.put("name", user.getFullName());
                    }
                    if (user.getLegalEntityId() != null) {
                        String entityId = user.getLegalEntityId().toString();
                        claims.put("entityId", entityId);
                        claims.put("entity_id", entityId);
                    }
                })
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(normalized, authoritiesOf(user), user.getId().toString()));

        chain.doFilter(request, response);
    }

    private static Collection<GrantedAuthority> authoritiesOf(AppUser user) {
        return user.getRoles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }

    /**
     * {@code auth_time} is an optional claim that must be requested on the API app registration.
     * When it is absent the step-up freshness check silently falls back to {@code iat}, which is
     * a materially weaker guarantee — worth one loud line rather than a silent downgrade.
     */
    private void warnOnceIfAuthTimeMissing(Jwt token) {
        if (authTimeAbsenceLogged || token.getClaim("auth_time") != null) {
            return;
        }
        authTimeAbsenceLogged = true;
        log.warn("Entra access tokens do not carry an 'auth_time' claim. Step-up freshness will fall back "
                + "to 'iat', which reflects token issuance rather than actual authentication. Add 'auth_time' "
                + "as an optional access-token claim on the API app registration. ({}=true)", AUTH_TIME_WARNED);
    }

    /** Exposed for tests; the production wiring is in {@code SecurityConfig}. */
    static List<String> normalizedClaimNames() {
        return List.of("sub", "entra_oid", "entra_tid", "roles", "email", "name", "entityId", "entity_id");
    }
}
