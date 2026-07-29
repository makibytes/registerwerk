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
import org.mockito.InjectMocks;
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

    @InjectMocks
    private CustomerOffboardingService service;

    private static AppUser user(boolean enabled) {
        AppUser u = new AppUser();
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
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("terminate rejects an entity that is already DISSOLVED")
    void terminate_rejectsAlreadyDissolved() {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setStatus(EntityStatus.DISSOLVED);
        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.terminate(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "merged away"))
                .isInstanceOf(IllegalStateException.class);
    }
}
