package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import de.makibytes.registerwerk.stepup.events.DualControlBootstrapUsedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DualControlService.requireIfNotBootstrap - the latch decides, not today's head count (C3)")
class DualControlServiceBootstrapTest {

    private final StepUpEnforcer enforcer = mock(StepUpEnforcer.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DualControlBootstrapLatch latch = mock(DualControlBootstrapLatch.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private DualControlService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DualControlService(mock(StepUpTokenValidator.class), enforcer, new DualControlProperties(),
                mock(DualControlTokenUseRepository.class), users, latch, publisher, tx);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("acr", "stepup").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/v1/admin/users")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("latch open and fewer than two enrolled administrators: the caller's step-up suffices and the bootstrap use is recorded")
    void bootstrapWhileLatchOpen() {
        when(latch.isComplete()).thenReturn(false);
        when(users.countEnabledTotpEnrolledUsersWithRole(AppUserRole.REGISTRY_ADMIN)).thenReturn(1L);

        DualControlGate.Outcome outcome = service.requireIfNotBootstrap("OPERATOR_USER_INVITE");

        assertThat(outcome.bootstrap()).isTrue();
        assertThat(outcome.approverId()).isNull();
        verify(publisher).publishEvent(any(DualControlBootstrapUsedEvent.class));
    }

    @Test
    @DisplayName("latch closed: even with ZERO enrolled administrators left, a second approver is required")
    void closedLatchNeverFallsBack() {
        when(latch.isComplete()).thenReturn(true);
        when(users.countEnabledTotpEnrolledUsersWithRole(AppUserRole.REGISTRY_ADMIN)).thenReturn(0L);

        assertThatThrownBy(() -> service.requireIfNotBootstrap("OPERATOR_USER_INVITE"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("dual control");
        verify(publisher, never()).publishEvent(any(DualControlBootstrapUsedEvent.class));
    }
}
