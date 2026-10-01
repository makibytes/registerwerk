package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerOffboardingService unit tests — the real customer off-ramp")
class CustomerOffboardingServiceTest {

    @Mock private LegalEntityRepository entityRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private ApplicationEventPublisher events;

    @Mock private de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository actionTokens;
    @Mock private de.makibytes.registerwerk.auth.api.SessionRevocationPort sessions;
    @Mock private de.makibytes.registerwerk.customer.api.EntityTaskPort taskPort;
    @Mock private de.makibytes.registerwerk.customer.api.OffboardingObligationSource source;

    private CustomerOffboardingService service;

    @org.junit.jupiter.api.BeforeEach
    void build() {
        service = new CustomerOffboardingService(entityRepository, userRepository, events, actionTokens, sessions,
                taskPort, List.of(source));
    }

    private static AppUser user(boolean enabled) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setEnabled(enabled);
        return u;
    }

    @Test
    @DisplayName("terminate disables every enabled user, sets CLOSED, and publishes CustomerOffboardedEvent")
    void terminate_disablesUsersAndClosesEntity() {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setStatus(EntityStatus.ACTIVE);
        AppUser enabledUser = user(true);
        AppUser alreadyDisabledUser = user(false);

        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId))
                .thenReturn(List.of(enabledUser, alreadyDisabledUser));
        when(entityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LegalEntity result = service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "customer requested exit");

        assertThat(result.getStatus()).isEqualTo(EntityStatus.CLOSED);
        assertThat(enabledUser.isEnabled()).isFalse();
        verify(userRepository).save(enabledUser);
        verify(userRepository, org.mockito.Mockito.never()).save(alreadyDisabledUser); // already disabled — no redundant save
        verify(actionTokens).invalidateAllForUser(enabledUser.getId());
        verify(actionTokens).invalidateAllForUser(alreadyDisabledUser.getId());
        verify(sessions).revokeAll(enabledUser.getId());

        ArgumentCaptor<CustomerOffboardedEvent> captor = ArgumentCaptor.forClass(CustomerOffboardedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entityId);
        assertThat(captor.getValue().reason()).isEqualTo("customer requested exit");
    }

    @Test
    @DisplayName("terminate rejects an entity that is already CLOSED")
    void terminate_rejectsAlreadyClosed() {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setStatus(EntityStatus.CLOSED);
        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "again?"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("terminate rejects an entity that is already DISSOLVED")
    void terminate_rejectsAlreadyDissolved() {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setStatus(EntityStatus.DISSOLVED);
        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "merged away"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("terminate with an unacknowledged obligation is a 409 listing it; acknowledged -> follow-up task, CLOSED")
    void terminate_obligationsNeedAcknowledgement() {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setStatus(EntityStatus.ACTIVE);
        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(entityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)).thenReturn(List.of());
        when(source.openObligations(entityId)).thenReturn(List.of(
                new de.makibytes.registerwerk.customer.api.OffboardingObligation("ISSUER_ASSET_LIVE", "a1", "issuer of live asset")));

        assertThatThrownBy(() -> service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("ISSUER_ASSET_LIVE:a1");
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);

        LegalEntity result = service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit",
                List.of(new de.makibytes.registerwerk.customer.api.ObligationAcknowledgement("ISSUER_ASSET_LIVE:a1", "successor named")));
        assertThat(result.getStatus()).isEqualTo(EntityStatus.CLOSED);
        verify(taskPort).open(org.mockito.ArgumentMatchers.eq(entityId), org.mockito.ArgumentMatchers.eq("ISSUER_ASSET_LIVE"),
                org.mockito.ArgumentMatchers.eq("a1"), org.mockito.ArgumentMatchers.contains("successor named"), any());
    }
}
