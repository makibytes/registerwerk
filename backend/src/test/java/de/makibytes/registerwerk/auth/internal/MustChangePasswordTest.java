package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.events.DefaultAdminSeededEvent;
import de.makibytes.registerwerk.auth.events.PasswordChangedEvent;
import de.makibytes.registerwerk.auth.internal.SessionStateService.UserState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Wave 5b item 3: must_change_password is enforced (restricted login token, 403 PASSWORD_CHANGE_REQUIRED elsewhere). */
@DisplayName("must_change_password enforcement")
class MustChangePasswordTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UUID userId = UUID.randomUUID();

    private AppUser user(boolean mustChange) {
        AppUser u = new AppUser();
        u.setId(userId);
        u.setEmail("admin@local");
        u.setRole(AppUserRole.REGISTRY_ADMIN);
        u.setAuthProvider(UserAuthProvider.LOCAL);
        u.setEnabled(true);
        u.setPasswordHash(encoder.encode("changeme"));
        u.setMustChangePassword(mustChange);
        return u;
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login of a must-change account returns a restricted, role-less password_change token")
    void loginReturnsARestrictedToken() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        JwtMintingService minter = new JwtMintingService(props);
        AppUserRepository users = mock(AppUserRepository.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        when(limiter.check(anyString(), anyString())).thenReturn(LoginAttemptLimiter.Decision.OPEN);
        AppUser u = user(true);
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(u));
        AuthService auth = new AuthService(users, encoder, minter, props, limiter);

        AuthService.LoginResult result = auth.login("admin@local", "changeme", "10.0.0.1");

        assertThat(result.passwordChangeRequired()).isTrue();
        Jwt jwt = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(props.getDevSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")).build().decode(result.token());
        assertThat(jwt.getClaimAsString("use")).isEqualTo(JwtMintingService.USE_PASSWORD_CHANGE);
        assertThat(jwt.getClaimAsStringList("roles")).isEmpty();
        assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plusSeconds(JwtMintingService.PASSWORD_CHANGE_TTL_SECONDS + 5));
    }

    @Test
    @DisplayName("login of an ordinary account is unchanged")
    void ordinaryLoginUnchanged() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        AppUserRepository users = mock(AppUserRepository.class);
        LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
        when(limiter.check(anyString(), anyString())).thenReturn(LoginAttemptLimiter.Decision.OPEN);
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(user(false)));
        AuthService auth = new AuthService(users, encoder, new JwtMintingService(props), props, limiter);

        assertThat(auth.login("admin@local", "changeme", "10.0.0.1").passwordChangeRequired()).isFalse();
    }

    // ── guard ────────────────────────────────────────────────────────────────

    private Jwt token(String use) {
        Instant now = Instant.now();
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId.toString())
                .issuer(JwtMintingService.LOCAL_ISSUER).issuedAt(now).expiresAt(now.plusSeconds(600)).claim("jti", "j1");
        if (use != null) b.claim("use", use);
        return b.build();
    }

    private UserSessionGuardFilter guard(boolean mustChange) {
        SessionStateService state = mock(SessionStateService.class);
        when(state.user(userId)).thenReturn(Optional.of(new UserState(true, null, null, true, false, mustChange)));
        return new UserSessionGuardFilter(state, new SimpleMeterRegistry(), true);
    }

    @Test
    @DisplayName("the guard restricts a must-change account and a password_change token, nothing else")
    void guardReason() {
        assertThat(guard(true).rejectionReason(token("session"))).isEqualTo("password_change_required");
        assertThat(guard(false).rejectionReason(token(JwtMintingService.USE_PASSWORD_CHANGE)))
                .isEqualTo("password_change_required");
        assertThat(guard(false).rejectionReason(token("session"))).isNull();
    }

    private MockHttpServletResponse run(UserSessionGuardFilter guard, String method, String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token(JwtMintingService.USE_PASSWORD_CHANGE), java.util.List.of()));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            guard.doFilter(new MockHttpServletRequest(method, path), response, chain);
            if (response.getStatus() == 200) {
                verify(chain).doFilter(any(), any());
            } else {
                verify(chain, never()).doFilter(any(), any());
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        return response;
    }

    @Test
    @DisplayName("a restricted session reaches change-password only: everything else is 403 PASSWORD_CHANGE_REQUIRED")
    void onlyChangePasswordIsReachable() throws Exception {
        UserSessionGuardFilter guard = guard(true);
        assertThat(run(guard, "POST", "/api/v1/auth/change-password").getStatus()).isEqualTo(200);
        assertThat(run(guard, "GET", "/api/v1/auth/session").getStatus()).isEqualTo(200);
        assertThat(run(guard, "POST", "/api/v1/public/auth/logout").getStatus()).isEqualTo(200);
        for (String[] call : new String[][] {{"GET", "/api/v1/assets"}, {"POST", "/api/v1/admin/users"},
                {"GET", "/api/v1/auth/change-password"}}) {
            MockHttpServletResponse r = run(guard, call[0], call[1]);
            assertThat(r.getStatus()).as(call[1]).isEqualTo(403);
            assertThat(r.getContentAsString()).contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"");
        }
    }

    // ── change-password ──────────────────────────────────────────────────────

    @Test
    @DisplayName("change-password verifies the current password, clears the flag, revokes the presenting token")
    void changePassword() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser u = user(true);
        when(users.findById(userId)).thenReturn(Optional.of(u));
        SessionRevocationPort revocation = mock(SessionRevocationPort.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        PasswordChangeService service = new PasswordChangeService(users, encoder, new JwtMintingService(props),
                revocation, events, props);

        assertThatThrownBy(() -> service.change(userId, "j1", null, "wrong", "Another-Passw0rd"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("current password");
        assertThatThrownBy(() -> service.change(userId, "j1", null, "changeme", "changeme"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("differ");
        assertThatThrownBy(() -> service.change(userId, "j1", null, "changeme", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(u.isMustChangePassword()).isTrue();

        PasswordChangeService.Changed changed = service.change(userId, "j1", null, "changeme", "Another-Passw0rd");

        assertThat(u.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("Another-Passw0rd", u.getPasswordHash())).isTrue();
        assertThat(changed.token()).isNotBlank();
        verify(revocation).revokeSession(org.mockito.ArgumentMatchers.eq("j1"), org.mockito.ArgumentMatchers.eq(userId),
                any(), org.mockito.ArgumentMatchers.eq("PASSWORD_CHANGED"));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(PasswordChangedEvent.class);
        assertThat(((PasswordChangedEvent) event.getValue()).wasForced()).isTrue();
        // the token minted right after the change must not be caught by the cut-off the change itself sets
        assertThat(u.getTokensValidAfter()).isBeforeOrEqualTo(Instant.now());
    }

    // ── seeder ───────────────────────────────────────────────────────────────

    private DefaultAdminSeededEvent seed(boolean production) {
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.getDefaultAdmin().setEmail("admin@local");
        props.getDefaultAdmin().setPassword("changeme");
        MockEnvironment env = new MockEnvironment();
        if (production) {
            env.setProperty("registerwerk.production-mode", "true");
        }
        DefaultAdminSeeder seeder = new DefaultAdminSeeder(users, encoder, props, events,
                mock(PlatformTransactionManager.class), mock(JdbcTemplate.class), env);
        seeder.run(null);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        DefaultAdminSeededEvent e = (DefaultAdminSeededEvent) event.getValue();
        assertThat(saved.getValue().isMustChangePassword()).isEqualTo(e.mustChangePassword());
        return e;
    }

    @Test
    @DisplayName("production forces the bootstrap admin to change the password; demo mode must not")
    void seederForcesChangeInProductionOnly() {
        assertThat(seed(true).mustChangePassword()).isTrue();
        assertThat(seed(false).mustChangePassword()).isFalse();
    }
}
