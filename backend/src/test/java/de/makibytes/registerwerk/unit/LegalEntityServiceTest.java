package de.makibytes.registerwerk.unit;

import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.customer.internal.EntityNumberGenerator;
import de.makibytes.registerwerk.customer.internal.LegalEntityService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityMergeRecordRepository;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.EntityNameHistoryRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;
import de.makibytes.registerwerk.customer.api.RiskTolerance;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import de.makibytes.registerwerk.customer.events.ClientClassifiedEvent;
import de.makibytes.registerwerk.customer.events.SuitabilityAssessmentRecordedEvent;
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
    private EntityMergeRecordRepository entityMergeRecordRepository;

    @Mock
    private SuitabilityAssessmentRepository suitabilityAssessmentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EntityNumberGenerator entityNumberGenerator;

    @InjectMocks
    private LegalEntityService legalEntityService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LegalEntity buildEntity() {
        LegalEntity entity = new LegalEntity();
        entity.setId(UUID.randomUUID());
        entity.setCurrentName("Acme GmbH");
        entity.setType(EntityType.ISSUER);
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
        verify(eventPublisher).publishEvent(any(Object.class));
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
    @DisplayName("updateEntity should save changes and publish an ENTITY_UPDATED audit event")
    void updateEntity_shouldSaveAndPublishAuditEvent() {
        LegalEntity entity = buildEntity();
        UUID actorId = UUID.randomUUID();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LegalEntity patch = new LegalEntity();
        patch.setCurrentName("Updated GmbH");

        legalEntityService.updateEntity(entity.getId(), patch, actorId);

        assertThat(entity.getCurrentName()).isEqualTo("Updated GmbH");
        verify(legalEntityRepository).save(entity);
        verify(eventPublisher).publishEvent(any(Object.class));
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

    @Test
    @DisplayName("mergeEntities dissolves the source entity and persists an EntityMergeRecord")
    void mergeEntities_dissolvesSourceAndPersistsRecord() {
        LegalEntity source = buildEntity();
        source.setStatus(EntityStatus.ACTIVE);
        LegalEntity target = buildEntity();
        UUID actorId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.of(2026, 6, 1);

        when(legalEntityRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(legalEntityRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entityMergeRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntityMergeRecord result = legalEntityService.mergeEntities(
                source.getId(), target.getId(), EntityMergeRecord.MergeType.ABSORPTION,
                effectiveDate, "Absorbed via share purchase agreement", actorId);

        assertThat(source.getStatus()).isEqualTo(EntityStatus.DISSOLVED);
        assertThat(result.getSourceEntityId()).isEqualTo(source.getId());
        assertThat(result.getTargetEntityId()).isEqualTo(target.getId());
        assertThat(result.getMergeType()).isEqualTo(EntityMergeRecord.MergeType.ABSORPTION);
        assertThat(result.getRecordedBy()).isEqualTo(actorId);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("mergeEntities rejects a dissolved target as the surviving entity")
    void mergeEntities_rejectsDissolvedTarget() {
        LegalEntity source = buildEntity();
        source.setStatus(EntityStatus.ACTIVE);
        LegalEntity target = buildEntity();
        target.setStatus(EntityStatus.DISSOLVED);

        when(legalEntityRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(legalEntityRepository.findById(target.getId())).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> legalEntityService.mergeEntities(
                source.getId(), target.getId(), EntityMergeRecord.MergeType.ABSORPTION,
                LocalDate.now(), null, UUID.randomUUID()))
            .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class)
            .hasMessageContaining("dissolved");
        assertThat(source.getStatus()).isEqualTo(EntityStatus.ACTIVE);
    }

    @Test
    @DisplayName("mergeEntities rejects merging an entity into itself")
    void mergeEntities_rejectsSelfMerge() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> legalEntityService.mergeEntities(
                id, id, EntityMergeRecord.MergeType.ABSORPTION, LocalDate.now(), null, UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be merged into itself");
    }

    // ── classifyClient / suitability (Track 5-1) ─────────────────────────────────

    @Test
    @DisplayName("classifyClient sets the category, timestamp, and classifier, and publishes an event")
    void classifyClient_setsCategoryAndPublishesEvent() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any(LegalEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID actorId = UUID.randomUUID();

        LegalEntity result = legalEntityService.classifyClient(entity.getId(), ClientCategory.PROFESSIONAL, actorId);

        assertThat(result.getClientCategory()).isEqualTo(ClientCategory.PROFESSIONAL);
        assertThat(result.getClientCategoryClassifiedAt()).isNotNull();
        assertThat(result.getClientCategoryClassifiedBy()).isEqualTo(actorId);

        ArgumentCaptor<ClientClassifiedEvent> captor = ArgumentCaptor.forClass(ClientClassifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entity.getId());
        assertThat(captor.getValue().clientCategory()).isEqualTo("PROFESSIONAL");
    }

    @Test
    @DisplayName("classifyClient throws for an unknown entity")
    void classifyClient_unknownEntity_throws() {
        UUID id = UUID.randomUUID();
        when(legalEntityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> legalEntityService.classifyClient(id, ClientCategory.RETAIL, UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("recordSuitabilityAssessment saves an assessment and publishes an event")
    void recordSuitabilityAssessment_savesAndPublishesEvent() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(suitabilityAssessmentRepository.save(any(SuitabilityAssessment.class))).thenAnswer(inv -> {
            SuitabilityAssessment a = inv.getArgument(0);
            return a;
        });
        UUID actorId = UUID.randomUUID();

        SuitabilityAssessment result = legalEntityService.recordSuitabilityAssessment(
                entity.getId(), KnowledgeExperienceLevel.ADVANCED, RiskTolerance.HIGH, 10, true, "notes", actorId);

        assertThat(result.getEntityId()).isEqualTo(entity.getId());
        assertThat(result.getKnowledgeExperience()).isEqualTo(KnowledgeExperienceLevel.ADVANCED);
        assertThat(result.getRiskTolerance()).isEqualTo(RiskTolerance.HIGH);
        assertThat(result.isFinancialSituationAdequate()).isTrue();

        ArgumentCaptor<SuitabilityAssessmentRecordedEvent> captor =
                ArgumentCaptor.forClass(SuitabilityAssessmentRecordedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entity.getId());
        assertThat(captor.getValue().knowledgeExperience()).isEqualTo("ADVANCED");
    }

    @Test
    @DisplayName("recordSuitabilityAssessment throws for an unknown entity")
    void recordSuitabilityAssessment_unknownEntity_throws() {
        UUID id = UUID.randomUUID();
        when(legalEntityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> legalEntityService.recordSuitabilityAssessment(
                id, KnowledgeExperienceLevel.BASIC, RiskTolerance.LOW, null, false, null, UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── assignRelationshipManager / listAssignedToRelationshipManager (Track 5-4) ────────────────

    @Test
    @DisplayName("assignRelationshipManager sets the field and publishes an event")
    void assignRelationshipManager_setsFieldAndPublishesEvent() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any(LegalEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID rmId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LegalEntity result = legalEntityService.assignRelationshipManager(entity.getId(), rmId, actorId);

        assertThat(result.getAssignedRelationshipManagerId()).isEqualTo(rmId);
        ArgumentCaptor<de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent> captor =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entity.getId());
        assertThat(captor.getValue().relationshipManagerId()).isEqualTo(rmId);
    }

    @Test
    @DisplayName("assignRelationshipManager(null) clears the assignment")
    void assignRelationshipManager_null_clearsAssignment() {
        LegalEntity entity = buildEntity();
        entity.setAssignedRelationshipManagerId(UUID.randomUUID());
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any(LegalEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        LegalEntity result = legalEntityService.assignRelationshipManager(entity.getId(), null, UUID.randomUUID());

        assertThat(result.getAssignedRelationshipManagerId()).isNull();
    }

    @Test
    @DisplayName("listAssignedToRelationshipManager delegates to the repository")
    void listAssignedToRelationshipManager_delegates() {
        UUID rmId = UUID.randomUUID();
        LegalEntity client = buildEntity();
        when(legalEntityRepository.findByAssignedRelationshipManagerId(rmId)).thenReturn(java.util.List.of(client));

        assertThat(legalEntityService.listAssignedToRelationshipManager(rmId)).containsExactly(client);
    }
}
