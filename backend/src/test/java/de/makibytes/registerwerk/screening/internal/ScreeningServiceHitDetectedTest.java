package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.NaturalPersonScreeningSubjectResolver;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningHitDto;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningResult;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.screening.events.ScreeningHitDetectedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2-19: a new (unreviewed) screening hit is published as {@link ScreeningHitDetectedEvent}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningService — ScreeningHitDetectedEvent")
class ScreeningServiceHitDetectedTest {

    @Mock SanctionsScreeningPort provider;
    @Mock ScreeningRunRepository runRepository;
    @Mock ScreeningHitRepository hitRepository;
    @Mock ApplicationEventPublisher events;
    @Mock LegalEntityRepository legalEntityRepository;
    @Mock NaturalPersonScreeningSubjectResolver naturalPersonResolver;

    private ScreeningService service;
    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScreeningService(List.of(provider), runRepository, hitRepository, events,
                legalEntityRepository, new SimpleMeterRegistry(), naturalPersonResolver, ScreeningPolicy.defaults());
        when(provider.providerName()).thenReturn("opensanctions");
        when(runRepository.save(any(ScreeningRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a HIT on the periodic re-screen publishes ScreeningHitDetectedEvent")
    void hit_publishesEvent() {
        when(provider.screen(any())).thenReturn(new ScreeningResult(List.of(
                new ScreeningHitDto("EU_FSF", "name", "Meridian", 0.93, null, "SANCTIONS"),
                new ScreeningHitDto("OFAC_SDN", "name", "Meridian", 0.71, null, "SANCTIONS")), null, null));

        service.screenEntity(entityId, "Meridian GmbH", "DE", null, ScreeningTrigger.PERIODIC_REFRESH);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        ScreeningHitDetectedEvent event = (ScreeningHitDetectedEvent) captor.getValue();
        assertThat(event.subjectId()).isEqualTo(entityId);
        assertThat(event.subjectType()).isEqualTo("LEGAL_ENTITY");
        assertThat(event.eventType()).isEqualTo("SCREENING_HIT_DETECTED");
        assertThat(event.payload())
                .containsEntry("trigger", "PERIODIC_REFRESH")
                .containsEntry("hitCount", 2)
                .containsEntry("maxMatchScore", 0.93);
    }

    @Test
    @DisplayName("a CLEAR run publishes nothing")
    void clear_publishesNothing() {
        when(provider.screen(any())).thenReturn(new ScreeningResult(List.of(), null, null));

        service.screenEntity(entityId, "Clean AG", "DE", null, ScreeningTrigger.PERIODIC_REFRESH);

        verify(events, never()).publishEvent(any(Object.class));
    }
}
