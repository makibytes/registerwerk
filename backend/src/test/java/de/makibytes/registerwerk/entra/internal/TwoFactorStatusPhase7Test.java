package de.makibytes.registerwerk.entra.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraUserMfaStatus;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.web.dto.TwoFactorStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TwoFactorStatusService Phase 7 (7A-08)")
class TwoFactorStatusPhase7Test {

    private final EntraDirectoryPort directory = mock(EntraDirectoryPort.class);
    private final PrincipalResolver resolver = mock(PrincipalResolver.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final Authentication auth = mock(Authentication.class);
    private TwoFactorStatusService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties authProps = new RegisterwerkAuthProperties();
        authProps.setEntraEnabled(true);
        RegisterwerkEntraProperties entraProps = new RegisterwerkEntraProperties();
        service = new TwoFactorStatusService(directory, resolver, users, authProps, entraProps);
        user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setAuthProvider(UserAuthProvider.ENTRA);
        user.setEntraObjectId(UUID.randomUUID());
        when(resolver.resolve(auth)).thenReturn(Optional.of(user));
        when(directory.isEnabled()).thenReturn(true);
    }

    private EntraUserMfaStatus ok(boolean registered) {
        return new EntraUserMfaStatus(true, EntraIdentityModel.WORKFORCE_MEMBER, registered, List.of(), Instant.now(), null);
    }

    @Test
    @DisplayName("two forced refreshes inside the window call Graph once")
    void forcedRefreshIsThrottled() {
        when(directory.getMfaStatus(anyString())).thenReturn(ok(true));
        service.statusFor(auth, true);
        service.statusFor(auth, true);
        verify(directory, times(1)).getMfaStatus(anyString());
    }

    @Test
    @DisplayName("a fresh recorded status serves plain GETs without a Graph call")
    void plainGetServedFromRecord() {
        user.setEntraMfaCheckedAt(Instant.now());
        user.setEntraMfaRegisteredAt(Instant.now());
        TwoFactorStatusResponse r = service.statusFor(auth, false);
        assertThat(r.registered()).isTrue();
        verify(directory, never()).getMfaStatus(anyString());
    }

    @Test
    @DisplayName("a Graph outage does not erase a recorded registration and is flagged stale")
    void graphOutageKeepsRegistration() {
        Instant registeredAt = Instant.now().minusSeconds(86_400);
        user.setEntraMfaRegisteredAt(registeredAt);
        user.setEntraMfaCheckedAt(Instant.now().minusSeconds(3_600));
        when(directory.getMfaStatus(anyString())).thenReturn(
                new EntraUserMfaStatus(true, EntraIdentityModel.WORKFORCE_MEMBER, false, List.of(), null,
                        "Two-factor status is temporarily unavailable."));
        TwoFactorStatusResponse r = service.statusFor(auth, false);
        assertThat(r.registered()).isTrue();
        assertThat(r.stale()).isTrue();
        assertThat(user.getEntraMfaRegisteredAt()).isEqualTo(registeredAt);
        verify(users, never()).save(user);
    }
}
