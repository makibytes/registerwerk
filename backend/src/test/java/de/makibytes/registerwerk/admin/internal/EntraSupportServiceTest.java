package de.makibytes.registerwerk.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.makibytes.registerwerk.admin.events.OperatorEntraTapIssuedEvent;
import de.makibytes.registerwerk.admin.web.dto.EntraMethodsResponse;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassRequest;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassResponse;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityGate;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraUnsupportedForIdentityModelException;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.api.TemporaryAccessPass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EntraSupportService")
class EntraSupportServiceTest {

    private static final String TAP_VALUE = "+drkzqAD";

    @Mock private EntraDirectoryPort directory;
    @Mock private EntraIdentityGate identityGate;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private EntraSupportService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID entraOid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RegisterwerkEntraProperties props = new RegisterwerkEntraProperties();
        props.setSupportEnabled(true);
        service = new EntraSupportService(directory, identityGate, appUserRepository, props, eventPublisher);
    }

    @Test
    @DisplayName("the TAP audit event never carries the pass itself")
    void tapAuditEvent_doesNotContainThePass() {
        AppUser user = entraUser();
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.WORKFORCE_MEMBER);
        when(identityGate.supportsTemporaryAccessPass(user)).thenReturn(true);
        when(directory.issueTemporaryAccessPass(anyString(), anyInt(), anyBoolean()))
                .thenReturn(new TemporaryAccessPass("tap-1", TAP_VALUE,
                        Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), 60, true));

        TemporaryAccessPassResponse response = service.issueTemporaryAccessPass(
                actor(), userId, new TemporaryAccessPassRequest(60, true), UUID.randomUUID());

        // The caller gets it exactly once…
        assertThat(response.value()).isEqualTo(TAP_VALUE);

        // …and the audit trail, which is long-retention and widely readable, must not.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        OperatorEntraTapIssuedEvent event = (OperatorEntraTapIssuedEvent) captor.getValue();
        assertThat(event.payload().toString()).doesNotContain(TAP_VALUE);
        assertThat(event.payload()).containsEntry("tapId", "tap-1");
        assertThat(event.payload()).containsEntry("lifetimeMinutes", 60);
    }

    @Test
    @DisplayName("a Temporary Access Pass is refused for an external guest, without calling Graph")
    void tap_refusedForExternalGuest() {
        AppUser user = entraUser();
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.WORKFORCE_GUEST);
        when(identityGate.supportsTemporaryAccessPass(user)).thenReturn(false);

        assertThatThrownBy(() -> service.issueTemporaryAccessPass(
                actor(), userId, new TemporaryAccessPassRequest(60, true), null))
                .isInstanceOf(EntraUnsupportedForIdentityModelException.class)
                .hasMessageContaining("external guest");

        verify(directory, never()).issueTemporaryAccessPass(anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("a federated user is refused before any Graph call is attempted")
    void federatedUser_refusedWithoutTouchingGraph() {
        AppUser user = entraUser();
        user.setEntraTenantId(UUID.randomUUID());
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.FEDERATED);

        assertThatThrownBy(() -> service.revokeSessions(actor(), userId))
                .isInstanceOf(EntraUnsupportedForIdentityModelException.class);

        // Graph would answer 404 for a principal that genuinely is not in our tenant, which
        // reads as a platform fault rather than the category error it is.
        verifyNoInteractions(directory);
    }

    @Test
    @DisplayName("listing methods for a federated user explains who to contact instead")
    void listMethods_federated_explains() {
        AppUser user = entraUser();
        UUID homeTenant = UUID.randomUUID();
        user.setEntraTenantId(homeTenant);
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.FEDERATED);

        EntraMethodsResponse response = service.listMethods(userId);

        assertThat(response.managedHere()).isFalse();
        assertThat(response.tapSupported()).isFalse();
        assertThat(response.message()).contains(homeTenant.toString()).contains("administrator");
        verifyNoInteractions(directory);
    }

    @Test
    @DisplayName("a reset clears the cached registration state and reports partial outcomes")
    void resetAllMethods_reportsPartialOutcome() {
        AppUser user = entraUser();
        user.setEntraMfaRegisteredAt(Instant.now());
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.WORKFORCE_MEMBER);
        when(directory.resetAllAuthMethods(entraOid.toString())).thenReturn(
                new EntraDirectoryPort.ResetOutcome(
                        List.of(new EntraAuthMethod("m1", EntraAuthMethodType.PHONE, "+49", false, null)),
                        List.of("MICROSOFT_AUTHENTICATOR (m2): refused")));

        var outcome = service.resetAllMethods(actor(), userId, UUID.randomUUID());

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.deleted()).hasSize(1);
        assertThat(outcome.failures()).hasSize(1);
        assertThat(user.getEntraMfaRegisteredAt()).isNull();
        verify(appUserRepository).save(user);
    }

    @Test
    @DisplayName("an account that has never signed in through Entra has nothing to manage")
    void neverSignedIn_isRefused() {
        AppUser user = entraUser();
        user.setEntraObjectId(null);
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(identityGate.classify(user)).thenReturn(EntraIdentityModel.WORKFORCE_MEMBER);

        assertThatThrownBy(() -> service.revokeSessions(actor(), userId))
                .isInstanceOf(EntraUnsupportedForIdentityModelException.class)
                .hasMessageContaining("never signed in");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser entraUser() {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("customer@test.local");
        user.setAuthProvider(UserAuthProvider.ENTRA);
        user.setEntraObjectId(entraOid);
        return user;
    }

    private Authentication actor() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .claim("sub", actorId.toString())
                .claim("roles", List.of("REGISTRY_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
