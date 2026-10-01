package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.internal.SessionStateService.ImpersonationState;
import de.makibytes.registerwerk.auth.internal.SessionStateService.UserState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("UserSessionGuardFilter.rejectionReason")
class UserSessionGuardFilterTest {

    private final SessionStateService state = mock(SessionStateService.class);
    private final UserSessionGuardFilter guard = new UserSessionGuardFilter(state, new SimpleMeterRegistry(), true);
    private final UUID userId = UUID.randomUUID();

    private Jwt jwt(String iss, Instant iat, String jti, boolean imp) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId.toString())
                .issuer(iss).issuedAt(iat).expiresAt(iat.plusSeconds(3600)).claim("roles", java.util.List.of("TRADER"));
        if (jti != null) b.claim("jti", jti);
        if (imp) b.claim("imp", true);
        return b.build();
    }

    private UserState user(boolean enabled, Instant tva) {
        return new UserState(enabled, tva, null, true, false);
    }

    @Test
    @DisplayName("an enabled user with a current token passes")
    void passes() {
        when(state.user(userId)).thenReturn(Optional.of(user(true, null)));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), "j", false))).isNull();
    }

    @Test
    @DisplayName("a disabled user's token is rejected (HS256)")
    void disabledLocal() {
        when(state.user(userId)).thenReturn(Optional.of(user(false, null)));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), "j", false))).isEqualTo("disabled");
    }

    @Test
    @DisplayName("a disabled user's normalised Entra/OIDC principal is rejected")
    void disabledOidc() {
        when(state.user(userId)).thenReturn(Optional.of(user(false, null)));
        assertThat(guard.rejectionReason(jwt("https://login.example/tenant/v2.0", Instant.now(), null, false)))
                .isEqualTo("disabled");
    }

    @Test
    @DisplayName("the iat cut-off applies to local tokens but not to OIDC tokens")
    void tokensValidAfter() {
        Instant now = Instant.now();
        when(state.user(userId)).thenReturn(Optional.of(user(true, now.plusSeconds(1))));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", now.minusSeconds(5), "j", false)))
                .isEqualTo("revoked_after");
        assertThat(guard.rejectionReason(jwt("https://login.example/tenant/v2.0", now.minusSeconds(5), null, false)))
                .isNull();
    }

    @Test
    @DisplayName("a logged-out jti is rejected; unknown users are rejected")
    void revokedAndUnknown() {
        when(state.user(userId)).thenReturn(Optional.of(user(true, null)));
        when(state.isRevoked("j")).thenReturn(true);
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), "j", false))).isEqualTo("revoked");
        when(state.user(any())).thenReturn(Optional.empty());
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), "k", false)))
                .isEqualTo("unknown_user");
    }

    @Test
    @DisplayName("an impersonation token needs a live session of an enabled REGISTRY_ADMIN actor")
    void impersonation() {
        UUID sid = UUID.randomUUID();
        when(state.user(userId)).thenReturn(Optional.of(user(true, null)));
        when(state.impersonation(sid)).thenReturn(Optional.of(
                new ImpersonationState(userId, UUID.randomUUID(), ImpersonationMode.READ_ONLY, false, false)));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), sid.toString(), true)))
                .isEqualTo("impersonation_ended");
        when(state.impersonation(sid)).thenReturn(Optional.of(
                new ImpersonationState(userId, UUID.randomUUID(), ImpersonationMode.READ_ONLY, true, false)));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), sid.toString(), true))).isNull();
        when(state.user(userId)).thenReturn(Optional.of(new UserState(true, null, null, false, false)));
        assertThat(guard.rejectionReason(jwt("registerwerk-local", Instant.now(), sid.toString(), true)))
                .isEqualTo("impersonation_actor_demoted");
    }
}
