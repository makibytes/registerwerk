package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This filter is the single point that makes the roughly one hundred existing
 * {@code SecurityUtils.extractUserId} call sites correct under Entra sign-in. Its contract is
 * therefore stated in terms of what those call sites observe.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntraPrincipalNormalizationFilter")
class EntraPrincipalNormalizationFilterTest {

    @Mock private PrincipalResolver principalResolver;

    private EntraPrincipalNormalizationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    private final UUID appUserId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();
    private final UUID entraOid = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new EntraPrincipalNormalizationFilter(principalResolver);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("rewrites sub to app_user.id so SecurityUtils.extractUserId returns a real row id")
    void rewritesSubToAppUserId() throws Exception {
        authenticate(entraJwt());
        when(principalResolver.requireUser(any())).thenReturn(account());

        filter.doFilter(request, response, chain);

        Authentication after = SecurityContextHolder.getContext().getAuthentication();
        assertThat(SecurityUtils.extractUserId(after)).isEqualTo(appUserId);
        assertThat(SecurityUtils.extractEntityId(after)).isEqualTo(entityId);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("preserves Entra's own identifiers under distinct claim names")
    void preservesEntraIdentifiers() throws Exception {
        authenticate(entraJwt());
        when(principalResolver.requireUser(any())).thenReturn(account());

        filter.doFilter(request, response, chain);

        Jwt token = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        assertThat(token.getClaimAsString("entra_oid")).isEqualTo(entraOid.toString());
        assertThat(token.getClaimAsString("entra_tid")).isEqualTo(tenantId.toString());
    }

    @Test
    @DisplayName("keeps step-up claims intact — the aspect reads acrs and auth_time off this token")
    void keepsStepUpClaims() throws Exception {
        Instant authTime = Instant.now().minusSeconds(30);
        authenticate(Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "entra-subject")
                .claim("oid", entraOid.toString())
                .claim("tid", tenantId.toString())
                .claim("acrs", List.of("c1"))
                .claim("auth_time", authTime.getEpochSecond())
                .claim("xms_cc", List.of("cp1"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build());
        when(principalResolver.requireUser(any())).thenReturn(account());

        filter.doFilter(request, response, chain);

        Jwt token = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        assertThat(token.getClaimAsStringList("acrs")).containsExactly("c1");
        assertThat((Long) token.getClaim("auth_time")).isEqualTo(authTime.getEpochSecond());
        assertThat(token.getClaimAsStringList("xms_cc")).containsExactly("cp1");
    }

    @Test
    @DisplayName("takes roles from the account row, not from the token")
    void rolesComeFromTheDatabase() throws Exception {
        // Token claims REGISTRY_ADMIN; the account row says INVESTOR. The row wins, so an
        // operator revoking a role does not have to wait for the token to expire.
        authenticate(Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "entra-subject")
                .claim("oid", entraOid.toString())
                .claim("roles", List.of("REGISTRY_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build());
        when(principalResolver.requireUser(any())).thenReturn(account());

        filter.doFilter(request, response, chain);

        Authentication after = SecurityContextHolder.getContext().getAuthentication();
        assertThat(after.getAuthorities()).extracting("authority").containsExactly("ROLE_INVESTOR");
        assertThat(SecurityUtils.extractRoles(after)).containsExactly("INVESTOR");
    }

    @Test
    @DisplayName("leaves a locally minted token completely untouched")
    void localToken_isNotRewritten() throws Exception {
        Jwt local = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .claim("iss", JwtMintingService.LOCAL_ISSUER)
                .claim("sub", appUserId.toString())
                .claim("roles", List.of("REGISTRY_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        authenticate(local);

        filter.doFilter(request, response, chain);

        Jwt after = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        assertThat(after).isSameAs(local);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("an unresolvable principal continues unchanged so the 403 comes from authorization")
    void unresolvablePrincipal_leavesAuthenticationAlone() throws Exception {
        Jwt jwt = entraJwt();
        authenticate(jwt);
        when(principalResolver.requireUser(any()))
                .thenThrow(new AccessDeniedException("no account"));

        filter.doFilter(request, response, chain);

        // Throwing from a filter would bypass the configured AccessDeniedHandler and surface as
        // a 500; letting it through produces the normal 403.
        Jwt after = ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).getToken();
        assertThat(after).isSameAs(jwt);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("an unauthenticated request passes straight through")
    void noAuthentication_passesThrough() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private AppUser account() {
        AppUser u = new AppUser();
        u.setId(appUserId);
        u.setEmail("customer@test.local");
        u.setFullName("Customer User");
        u.setLegalEntityId(entityId);
        u.setEnabled(true);
        u.setRoles(Set.of(AppUserRole.INVESTOR));
        return u;
    }

    private Jwt entraJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "entra-subject-not-a-uuid")
                .claim("oid", entraOid.toString())
                .claim("tid", tenantId.toString())
                .claim("preferred_username", "customer@test.local")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }
}
