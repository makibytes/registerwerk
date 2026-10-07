package de.makibytes.registerwerk.admin.internal;

import de.makibytes.registerwerk.admin.web.dto.ImpersonateRequest;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminImpersonationService (T6-05: production = read-only support sessions)")
class AdminImpersonationServiceTest {

    private final ImpersonationSessionRepository sessions = mock(ImpersonationSessionRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
    private final UUID actorId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();

    private AdminImpersonationService service(boolean production) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        AppUser actor = mock(AppUser.class);
        when(actor.isEnabled()).thenReturn(true);
        when(users.findById(actorId)).thenReturn(Optional.of(actor));
        LegalEntity target = mock(LegalEntity.class);
        when(target.getStatus()).thenReturn(EntityStatus.ACTIVE);
        when(target.getId()).thenReturn(entityId);
        when(target.getCurrentName()).thenReturn("Acme GmbH");
        when(entities.findById(entityId)).thenReturn(Optional.of(target));
        when(sessions.save(any(ImpersonationSession.class))).thenAnswer(i -> i.getArgument(0));
        return new AdminImpersonationService(sessions, users, entities, events, props, env, "http://localhost:44201");
    }

    private JwtAuthenticationToken caller(AppUserRole role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(actorId.toString())
                .claim("roles", List.of(role.name())).issuedAt(Instant.now()).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    private ImpersonateRequest request() {
        return new ImpersonateRequest(entityId, "Customer support ticket investigation", null);
    }

    @Test
    @DisplayName("production: ACT_ON_BEHALF is refused with a clear message, even with a second approver")
    void productionRefusesActOnBehalf() {
        var svc = service(true);
        assertThatThrownBy(() -> svc.impersonate(caller(AppUserRole.REGISTRY_ADMIN), request(),
                ImpersonationMode.ACT_ON_BEHALF, approverId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("read-only");
        verify(sessions, never()).save(any());
    }

    @Test
    @DisplayName("demo: ACT_ON_BEHALF with a second approver still works")
    void demoKeepsActOnBehalf() {
        var response = service(false).impersonate(caller(AppUserRole.REGISTRY_ADMIN), request(),
                ImpersonationMode.ACT_ON_BEHALF, approverId);
        assertThat(response.mode()).isEqualTo("ACT_ON_BEHALF");
    }

    @Test
    @DisplayName("production: REGISTRY_ADMIN keeps READ_ONLY impersonation")
    void productionAdminReadOnly() {
        var response = service(true).impersonate(caller(AppUserRole.REGISTRY_ADMIN), request(),
                ImpersonationMode.READ_ONLY, null);
        assertThat(response.mode()).isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("SUPPORT_AGENT may start READ_ONLY sessions in production and in demo mode")
    void supportAgentReadOnly() {
        assertThat(service(true).impersonate(caller(AppUserRole.SUPPORT_AGENT), request(),
                ImpersonationMode.READ_ONLY, null).mode()).isEqualTo("READ_ONLY");
        assertThat(service(false).impersonate(caller(AppUserRole.SUPPORT_AGENT), request(),
                ImpersonationMode.READ_ONLY, null).mode()).isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("SUPPORT_AGENT can never act on behalf, not even in demo mode")
    void supportAgentNeverActsOnBehalf() {
        var svc = service(false);
        assertThatThrownBy(() -> svc.impersonate(caller(AppUserRole.SUPPORT_AGENT), request(),
                ImpersonationMode.ACT_ON_BEHALF, approverId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("other operator roles may not impersonate at all")
    void otherRolesRefused() {
        var svc = service(false);
        assertThatThrownBy(() -> svc.impersonate(caller(AppUserRole.COMPLIANCE_OFFICER), request(),
                ImpersonationMode.READ_ONLY, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
