package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.customer.EntityNumberGenerator;
import de.makibytes.registerwerk.application.customer.LegalEntityService;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.entity.EntityNameHistory;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.EntityNameHistoryRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LegalEntityService unit tests")
class LegalEntityServiceTest {

    @Mock
    private LegalEntityRepository legalEntityRepository;

    @Mock
    private EntityNameHistoryRepository entityNameHistoryRepository;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private EntityNumberGenerator entityNumberGenerator;

    @InjectMocks
    private LegalEntityService legalEntityService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LegalEntity buildEntity() {
        LegalEntity entity = new LegalEntity();
        entity.setId(UUID.randomUUID());
        entity.setCurrentName("Acme GmbH");
        entity.setType(EntityType.COMPANY);
        entity.setStatus(EntityStatus.PENDING_ONBOARDING);
        return entity;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createEntity should assign the generated entity number before saving")
    void createEntity_shouldGenerateEntityNumber() {
        LegalEntity entity = buildEntity();
        UUID actorId = UUID.randomUUID();
        when(entityNumberGenerator.generateEntityNumber()).thenReturn("ENT-2026-000001");
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LegalEntity result = legalEntityService.createEntity(entity, actorId);

        assertThat(result.getEntityNumber()).isEqualTo("ENT-2026-000001");
        verify(entityNumberGenerator).generateEntityNumber();
    }

    @Test
    @DisplayName("createEntity should persist the entity and publish an ENTITY_CREATED audit event")
    void createEntity_shouldSaveAndPublishAuditEvent() {
        LegalEntity entity = buildEntity();
        UUID actorId = UUID.randomUUID();
        when(entityNumberGenerator.generateEntityNumber()).thenReturn("ENT-2026-000002");
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        legalEntityService.createEntity(entity, actorId);

        verify(legalEntityRepository).save(entity);
        verify(auditEventPublisher).publish(
            eq("ENTITY_CREATED"), eq("LegalEntity"), any(UUID.class), eq(actorId), any(), any());
    }

    @Test
    @DisplayName("getEntity should throw EntityNotFoundException when the ID does not exist")
    void getEntity_shouldThrowWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(legalEntityRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> legalEntityService.getEntity(unknownId))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining(unknownId.toString());
    }

    @Test
    @DisplayName("suspendEntity should set status to SUSPENDED and save the entity")
    void suspendEntity_shouldChangeStatus() {
        LegalEntity entity = buildEntity();
        UUID actorId = UUID.randomUUID();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        legalEntityService.suspendEntity(entity.getId(), actorId);

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.SUSPENDED);
        verify(legalEntityRepository).save(entity);
    }

    @Test
    @DisplayName("dissolveEntity should set status to DISSOLVED and save the entity")
    void dissolveEntity_shouldChangeStatus() {
        LegalEntity entity = buildEntity();
        UUID actorId = UUID.randomUUID();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        legalEntityService.dissolveEntity(entity.getId(), actorId);

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.DISSOLVED);
        verify(legalEntityRepository).save(entity);
    }

    @Test
    @DisplayName("renameEntity should persist an EntityNameHistory record with the previous and new name")
    void renameEntity_shouldSaveHistoryRecord() {
        LegalEntity entity = buildEntity();
        entity.setCurrentName("Old Name GmbH");
        UUID actorId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.of(2026, 1, 1);

        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entityNameHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        legalEntityService.renameEntity(entity.getId(), "New Name AG", effectiveDate, actorId);

        ArgumentCaptor<EntityNameHistory> historyCaptor = ArgumentCaptor.forClass(EntityNameHistory.class);
        verify(entityNameHistoryRepository).save(historyCaptor.capture());
        EntityNameHistory saved = historyCaptor.getValue();

        assertThat(saved.getPreviousName()).isEqualTo("Old Name GmbH");
        assertThat(saved.getNewName()).isEqualTo("New Name AG");
        assertThat(saved.getEffectiveDate()).isEqualTo(effectiveDate);
        assertThat(entity.getCurrentName()).isEqualTo("New Name AG");
    }
}
