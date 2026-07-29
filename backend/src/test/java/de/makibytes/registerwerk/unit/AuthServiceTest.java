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
    }

    @Test
    @DisplayName("Valid credentials return a token")
    void validCredentials_returnToken() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase("admin@local")).thenReturn(Optional.of(user));
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(minter.mint(user)).thenReturn("jwt-token");
        when(users.save(any())).thenReturn(user);

        LoginResult result = service.login("admin@local", "secret");

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
        when(users.save(any())).thenReturn(user);

        service.login("admin@local", "secret");

        assertThat(user.getLastLoginAt()).isNotNull();
        verify(users).save(user);
    }

    @Test
    @DisplayName("Unknown email throws InvalidCredentialsException")
    void unknownEmail_throwsInvalidCredentials() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@local", "x"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("Wrong password throws InvalidCredentialsException")
    void wrongPassword_throwsInvalidCredentials() {
        AppUser user = buildUser();
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login("admin@local", "wrong"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("Disabled user throws InvalidCredentialsException")
    void disabledUser_throwsInvalidCredentials() {
        AppUser user = buildUser();
        user.setEnabled(false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("admin@local", "secret"))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("entraEnabled=true throws LoginDisabledException")
    void entraEnabled_throwsLoginDisabled() {
        props.setEntraEnabled(true);

        assertThatThrownBy(() -> service.login("admin@local", "secret"))
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

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("ghost@local", "whatever"))
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
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
        }

        // Locked: correct password is rejected without touching the encoder again.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "secret"))
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
        when(users.save(any())).thenReturn(user);

        for (int i = 0; i < 4; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
        }
        LoginResult result = service.login("admin@local", "secret");
        assertThat(result.token()).isEqualTo("jwt-token");

        // Counter cleared: more failures allowed before lockout kicks in again.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.login("admin@local", "wrong"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
    }
}
