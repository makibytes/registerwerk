package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.events.EntityCreatedEvent;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EntityCreatedScreeningListener unit tests")
class EntityCreatedScreeningListenerTest {

    @Mock
    private ScreeningService screeningService;

    @Mock
    private LegalEntityRepository legalEntityRepository;

    private EntityCreatedScreeningListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        listener = new EntityCreatedScreeningListener(screeningService, legalEntityRepository);
    }

    private static LegalEntity entity(UUID id) {
        LegalEntity entity = new LegalEntity();
        entity.setId(id);
        entity.setCurrentName("Beispiel GmbH");
        entity.setRegistrationCountry("DE");
        entity.setLeiCode("529900T8BM49AURSDO55");
        return entity;
    }

    @Test
    @DisplayName("triggers an ENTITY_ONBOARDING screening run for the newly created entity")
    void onEntityCreated_triggersScreening() {
        UUID entityId = UUID.randomUUID();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity(entityId)));

        listener.onEntityCreated(new EntityCreatedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of()));

        verify(screeningService).screenEntity(entityId, "Beispiel GmbH", "DE",
                "529900T8BM49AURSDO55", ScreeningTrigger.ENTITY_ONBOARDING);
    }

    @Test
    @DisplayName("skips gracefully when the entity no longer exists")
    void onEntityCreated_skipsWhenEntityGone() {
        UUID entityId = UUID.randomUUID();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.empty());

        listener.onEntityCreated(new EntityCreatedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of()));

        verify(screeningService, never()).screenEntity(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a screening-provider failure does not propagate (the entity record must still exist)")
    void onEntityCreated_survivesScreeningFailure() {
        UUID entityId = UUID.randomUUID();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity(entityId)));
        org.mockito.Mockito.doThrow(new RuntimeException("provider outage"))
                .when(screeningService).screenEntity(any(), any(), any(), any(), any());

        listener.onEntityCreated(new EntityCreatedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", Map.of()));
    }
}
