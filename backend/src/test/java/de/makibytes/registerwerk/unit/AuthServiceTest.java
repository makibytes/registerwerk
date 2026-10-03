package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.auth.internal.AuthService;
import de.makibytes.registerwerk.auth.internal.LoginAttemptLimiter;
import de.makibytes.registerwerk.auth.internal.AuthService.LoginResult;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.shared.InvalidCredentialsException;
import de.makibytes.registerwerk.shared.LoginDisabledException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService unit tests")
class AuthServiceTest {

    @Mock private AppUserRepository users;
    @Mock private PasswordEncoder encoder;
    @Mock private JwtMintingService minter;
    // LoginAttemptLimiter is now DB-backed (V4 migration) — mocked here since these tests
    // exercise AuthService's login/lockout INTERACTION with the limiter, not the limiter's
    // own SQL. Default mock behaviour (isBlocked=false, no-op record*) matches the
    // happy-path "not currently locked out" case these tests need.
    @Mock private LoginAttemptLimiter attemptLimiter;

    private RegisterwerkAuthProperties props;
    private AuthService service;

    @BeforeEach
    void setUp() {
        props = new RegisterwerkAuthProperties();
        props.setEntraEnabled(false);
        service = new AuthService(users, encoder, minter, props, attemptLimiter);
        org.mockito.Mockito.lenient().when(attemptLimiter.check(anyString(), anyString()))
                .thenReturn(LoginAttemptLimiter.Decision.OPEN);
    }

    @Test
    @DisplayName("Valid credentials return a token")
    void validCredentials_returnToken() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(user));
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(minter.mint(user)).thenReturn("jwt-token");

        LoginResult result = service.login("admin@local", "secret", "10.0.0.1");

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.roles()).containsExactly("REGISTRY_ADMIN");
        assertThat(result.userId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("lastLoginAt is updated on successful login")
    void successfulLogin_updatesLastLoginAt() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        when(minter.mint(any())).thenReturn("token");

        service.login("admin@local", "secret", "10.0.0.1");

        assertThat(user.getLastLoginAt()).isNotNull();
        // Only the login timestamp is written - the user was read before the BCrypt work, in another transaction.
        verify(users).touchLastLogin(org.mockito.ArgumentMatchers.eq(user.getId()), any());
        verify(users, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("Unknown email throws InvalidCredentialsException")
    void unknownEmail_throwsInvalidCredentials() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@local", "x", "10.0.0.1"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("Wrong password throws InvalidCredentialsException")
    void wrongPassword_throwsInvalidCredentials() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login("admin@local", "wrong", "10.0.0.1"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("Disabled user throws InvalidCredentialsException")
    void disabledUser_throwsInvalidCredentials() {
        AppUser user = buildUser();
        user.setEnabled(false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("admin@local", "secret", "10.0.0.1"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("entraEnabled=true throws LoginDisabledException")
    void entraEnabled_throwsLoginDisabled() {
        props.setEntraEnabled(true);

        assertThatThrownBy(() -> service.login("admin@local", "secret", "10.0.0.1"))
            .isInstanceOf(LoginDisabledException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AppUser buildUser() {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@local");
        user.setPasswordHash("hash");
        user.setRole(AppUserRole.REGISTRY_ADMIN);
        user.setEnabled(true);
        return user;
    }

    @Test
    @DisplayName("Unknown email burns a dummy hash comparison (anti-enumeration)")
    void unknownEmail_burnsDummyComparison() {
        when(users.findByEmailIgnoreCase("ghost@local")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("ghost@local", "whatever", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);

        // The encoder must still be exercised so timing does not reveal account existence.
        verify(encoder).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Account locks after max failed attempts — even the correct password is rejected")
    void bruteForce_locksAccount() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong", "10.0.0.1"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
        }

        // Locked: correct password is rejected without touching the encoder again.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "secret", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("Successful login resets the failure counter")
    void successfulLogin_resetsCounter() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", "hash")).thenReturn(false);
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(minter.mint(user)).thenReturn("jwt-token");

        for (int i = 0; i < 4; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong", "10.0.0.1"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
        }
        LoginResult result = service.login("admin@local", "secret", "10.0.0.1");
        assertThat(result.token()).isEqualTo("jwt-token");

        // Counter cleared: more failures allowed before lockout kicks in again.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("K3 6-10 / C4: a throttled (account, source) pair is answered with a retry-after before any password work")
    void blockedPair_refusedWithoutPasswordWork() {
        when(attemptLimiter.check("admin@local", "10.0.0.1")).thenReturn(LoginAttemptLimiter.Decision.refuseFor(42.2));

        assertThatThrownBy(() -> service.login("admin@local", "secret", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.LoginThrottledException.class)
                .extracting(e -> ((de.makibytes.registerwerk.shared.LoginThrottledException) e).retryAfterSeconds())
                .isEqualTo(43L);

        org.mockito.Mockito.verifyNoInteractions(encoder, users);
    }

    @Test
    @DisplayName("C4: the throttle is uniform for known and unknown e-mails and the request thread never waits on it")
    void throttle_isUniformAndDoesNotSleep() {
        when(attemptLimiter.check(anyString(), anyString())).thenReturn(LoginAttemptLimiter.Decision.refuseFor(2));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.login("ghost@local", "x", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.LoginThrottledException.class);
        assertThatThrownBy(() -> service.login("admin@local", "x", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.LoginThrottledException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).as("no Thread.sleep on the request thread").isLessThan(500);
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    @Test
    @DisplayName("C4: login holds no database transaction - a transaction would pin a pooled connection through the BCrypt work and any wait")
    void loginHoldsNoTransaction() throws Exception {
        assertThat(AuthService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
        assertThat(AuthService.class.getMethod("login", String.class, String.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
    }

    @Test
    @DisplayName("K3 6-10: failures are recorded with the source address")
    void failure_recordedWithSource() {
        when(users.findByEmailIgnoreCase("ghost@local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost@local", "x", "203.0.113.9")).isInstanceOf(InvalidCredentialsException.class);

        verify(attemptLimiter).recordFailure("ghost@local", "203.0.113.9");
    }
}
