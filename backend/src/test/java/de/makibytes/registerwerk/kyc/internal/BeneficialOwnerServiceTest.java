package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerAddedEvent;
import de.makibytes.registerwerk.kyc.events.BeneficialOwnerCeasedEvent;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BeneficialOwnerService — UBO registration triggers real screening (finding #11)")
class BeneficialOwnerServiceTest {

    @Mock private BeneficialOwnerRepository beneficialOwnerRepository;
    @Mock private NaturalPersonRepository naturalPersonRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ScreeningGate screeningGate;

    @InjectMocks
    private BeneficialOwnerService service;

    private BeneficialOwnerRequest.NaturalPersonInput personInput() {
        return new BeneficialOwnerRequest.NaturalPersonInput(
                "Jane", "Doe", null, "DE", "DE", null, null, null, null, null, null, "DE");
    }

    @Test
    @DisplayName("addBeneficialOwner persists the NaturalPerson + BeneficialOwner link and triggers screening")
    void addBeneficialOwner_persistsAndTriggersScreening() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID boId = UUID.randomUUID();

        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(new LegalEntity()));
        when(naturalPersonRepository.save(any(NaturalPerson.class))).thenAnswer(inv -> {
            NaturalPerson p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", personId); // simulate JPA assigning the @GeneratedValue id
            return p;
        });
        when(beneficialOwnerRepository.save(any(BeneficialOwner.class))).thenAnswer(inv -> {
            BeneficialOwner bo = inv.getArgument(0);
            ReflectionTestUtils.setField(bo, "id", boId);
            return bo;
        });

        BeneficialOwner result = service.addBeneficialOwner(
                entityId, personInput(), new java.math.BigDecimal("30.00"),
                BeneficialOwner.ControlType.DIRECT_OWNERSHIP, "onboarding-form", actorId, "COMPLIANCE_OFFICER");

        assertThat(result.getEntityId()).isEqualTo(entityId);
        assertThat(result.getControlType()).isEqualTo(BeneficialOwner.ControlType.DIRECT_OWNERSHIP);

        verify(screeningGate).screenNaturalPerson(eq(personId), eq("Jane Doe"), eq("DE"), eq(ScreeningTrigger.BENEFICIAL_OWNER_ADD));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(BeneficialOwnerAddedEvent.class);
        assertThat(((BeneficialOwnerAddedEvent) events.getValue()).actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("addBeneficialOwner throws when the legal entity does not exist")
    void addBeneficialOwner_rejectsUnknownEntity() {
        UUID entityId = UUID.randomUUID();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addBeneficialOwner(
                entityId, personInput(), null, BeneficialOwner.ControlType.OTHER_CONTROL, null,
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(naturalPersonRepository, never()).save(any());
        verify(beneficialOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("addBeneficialOwner still persists the UBO record when the screening trigger itself fails")
    void addBeneficialOwner_survivesScreeningProviderFailure() {
        UUID entityId = UUID.randomUUID();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(new LegalEntity()));
        when(naturalPersonRepository.save(any(NaturalPerson.class))).thenAnswer(inv -> {
            NaturalPerson p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });
        when(beneficialOwnerRepository.save(any(BeneficialOwner.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("provider unavailable"))
                .when(screeningGate).screenNaturalPerson(any(), anyString(), any(), eq(ScreeningTrigger.BENEFICIAL_OWNER_ADD));

        BeneficialOwner result = service.addBeneficialOwner(
                entityId, personInput(), null, BeneficialOwner.ControlType.TRUSTEE, null,
                UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result).isNotNull();
        verify(beneficialOwnerRepository).save(any(BeneficialOwner.class));
    }

    @Test
    @DisplayName("ceaseBeneficialOwner sets ceasedAt and publishes an audit event")
    void ceaseBeneficialOwner_setsCeasedAtAndPublishesEvent() {
        UUID boId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        BeneficialOwner bo = new BeneficialOwner();
        bo.setEntityId(entityId);
        ReflectionTestUtils.setField(bo, "id", boId);
        when(beneficialOwnerRepository.findByIdAndEntityId(boId, entityId)).thenReturn(Optional.of(bo));
        when(beneficialOwnerRepository.save(any(BeneficialOwner.class))).thenAnswer(inv -> inv.getArgument(0));

        BeneficialOwner result = service.ceaseBeneficialOwner(
                entityId, boId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.getCeasedAt()).isNotNull();
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(BeneficialOwnerCeasedEvent.class);
    }

    @Test
    @DisplayName("ceaseBeneficialOwner rejects an already-ceased link")
    void ceaseBeneficialOwner_rejectsAlreadyCeased() {
        UUID boId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        BeneficialOwner bo = new BeneficialOwner();
        bo.setEntityId(entityId);
        bo.setCeasedAt(java.time.Instant.now());
        when(beneficialOwnerRepository.findByIdAndEntityId(boId, entityId)).thenReturn(Optional.of(bo));

        assertThatThrownBy(() -> service.ceaseBeneficialOwner(
                entityId, boId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
