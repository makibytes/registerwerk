package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.auth.internal.AuthService;
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

    private RegisterwerkAuthProperties props;
    private AuthService service;

    @BeforeEach
    void setUp() {
        props = new RegisterwerkAuthProperties();
        props.setEntraEnabled(false);
        service = new AuthService(users, encoder, minter, props);
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
}
