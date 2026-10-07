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
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private de.makibytes.registerwerk.customer.internal.CustomerOffboardingService offboardingService;

    @Mock
    private de.makibytes.registerwerk.customer.api.EntityTaskPort taskPort;

    @Mock
    private de.makibytes.registerwerk.stepup.api.DualControlGate dualControlGate;

    @Mock
    private de.makibytes.registerwerk.customer.api.EntityReactivationGuard guard;

    private LegalEntityService legalEntityService;

    @org.junit.jupiter.api.BeforeEach
    void buildService() {
        legalEntityService = new LegalEntityService(legalEntityRepository, entityNameHistoryRepository,
                entityMergeRecordRepository, suitabilityAssessmentRepository, eventPublisher, entityNumberGenerator,
                appUserRepository, offboardingService, taskPort, dualControlGate, java.util.List.of(guard));
    }

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
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(any(Object.class));
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
    @DisplayName("suspendEntity moves ACTIVE to SUSPENDED and records the reason")
    void suspendEntity_shouldChangeStatus() {
        LegalEntity entity = buildEntity();
        entity.setStatus(EntityStatus.ACTIVE);
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        legalEntityService.suspendEntity(entity.getId(), UUID.randomUUID(), "sanctions inquiry");

        assertThat(entity.getStatus()).isEqualTo(EntityStatus.SUSPENDED);
        ArgumentCaptor<de.makibytes.registerwerk.customer.events.EntitySuspendedEvent> c =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.customer.events.EntitySuspendedEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        assertThat(c.getValue().payload()).containsEntry("reason", "sanctions inquiry").containsEntry("from", "ACTIVE");
    }

    @Test
    @DisplayName("state machine: reactivating CLOSED, DISSOLVED or PENDING_ONBOARDING is a 409, ACTIVE cannot be suspended twice")
    void reactivate_invalidSources_areRefused() {
        for (EntityStatus s : new EntityStatus[]{EntityStatus.CLOSED, EntityStatus.DISSOLVED,
                EntityStatus.PENDING_ONBOARDING, EntityStatus.ACTIVE}) {
            LegalEntity entity = buildEntity();
            entity.setStatus(s);
            when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            assertThatThrownBy(() -> legalEntityService.reactivateEntity(entity.getId(), UUID.randomUUID(), "why"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
            assertThat(entity.getStatus()).isEqualTo(s);
        }
        LegalEntity closed = buildEntity();
        closed.setStatus(EntityStatus.CLOSED);
        when(legalEntityRepository.findById(closed.getId())).thenReturn(Optional.of(closed));
        assertThatThrownBy(() -> legalEntityService.suspendEntity(closed.getId(), UUID.randomUUID(), "x"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("reactivate is refused while a guard reports a blocker or KYC is expired; allowed otherwise")
    void reactivate_guards() {
        LegalEntity entity = buildEntity();
        entity.setStatus(EntityStatus.SUSPENDED);
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(guard.blockers(entity.getId())).thenReturn(java.util.List.of("active Sperrvermerk"));
        assertThatThrownBy(() -> legalEntityService.reactivateEntity(entity.getId(), UUID.randomUUID(), "ok"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("Sperrvermerk");
        when(guard.blockers(entity.getId())).thenReturn(java.util.List.of());
        entity.setKycStatus(de.makibytes.registerwerk.customer.api.KycStatus.EXPIRED);
        assertThatThrownBy(() -> legalEntityService.reactivateEntity(entity.getId(), UUID.randomUUID(), "ok"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class);
        entity.setKycStatus(de.makibytes.registerwerk.customer.api.KycStatus.APPROVED);
        legalEntityService.reactivateEntity(entity.getId(), UUID.randomUUID(), "cleared");
        assertThat(entity.getStatus()).isEqualTo(EntityStatus.ACTIVE);
    }

    @Test
    @DisplayName("T6-12: CLOSED/DISSOLVED -> PENDING_REACTIVATION resets KYC, re-screens, raises tasks and audits; never ACTIVE")
    void requestReinstatement_fromTerminalStates() {
        for (EntityStatus from : new EntityStatus[]{EntityStatus.CLOSED, EntityStatus.DISSOLVED}) {
            org.mockito.Mockito.clearInvocations(eventPublisher, taskPort);
            LegalEntity entity = buildEntity();
            entity.setStatus(from);
            entity.setKycStatus(de.makibytes.registerwerk.customer.api.KycStatus.APPROVED);
            entity.setKycExpiryDate(LocalDate.now().plusMonths(6));
            when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID actor = UUID.randomUUID();

            legalEntityService.requestReinstatement(entity.getId(), actor, "court decision overturned", "AG Berlin 12 HRB 123/26");

            assertThat(entity.getStatus()).isEqualTo(EntityStatus.PENDING_REACTIVATION);
            assertThat(entity.getKycStatus()).isEqualTo(de.makibytes.registerwerk.customer.api.KycStatus.NOT_STARTED);
            assertThat(entity.getKycExpiryDate()).isNull();
            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
            de.makibytes.registerwerk.customer.events.EntityReinstatementRequestedEvent audited =
                    (de.makibytes.registerwerk.customer.events.EntityReinstatementRequestedEvent) events.getAllValues().get(0);
            assertThat(audited.eventType()).isEqualTo("ENTITY_REINSTATEMENT_REQUESTED");
            assertThat(audited.payload()).containsEntry("legalReference", "AG Berlin 12 HRB 123/26")
                    .containsEntry("reason", "court decision overturned").containsEntry("from", from.name())
                    .containsEntry("to", "PENDING_REACTIVATION").containsEntry("previousKycStatus", "APPROVED");
            // mandatory re-screening goes through the existing risk-data event (screening module listens)
            assertThat(events.getAllValues().get(1)).isInstanceOf(de.makibytes.registerwerk.customer.events.EntityRiskDataChangedEvent.class);
            verify(taskPort).open(eq(entity.getId()), eq("REINSTATEMENT_KYC_REQUIRED"), eq(""), anyString(), eq(actor));
            verify(taskPort).open(eq(entity.getId()), eq("REINSTATEMENT_USERS_REVIEW"), eq(""), anyString(), eq(actor));
        }
    }

    @Test
    @DisplayName("T6-12: reinstatement needs a terminal entity, a reason and a legal reference")
    void requestReinstatement_refusals() {
        for (EntityStatus s : new EntityStatus[]{EntityStatus.ACTIVE, EntityStatus.SUSPENDED,
                EntityStatus.PENDING_ONBOARDING, EntityStatus.PENDING_REACTIVATION}) {
            LegalEntity entity = buildEntity();
            entity.setStatus(s);
            when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            assertThatThrownBy(() -> legalEntityService.requestReinstatement(entity.getId(), UUID.randomUUID(), "why", "ref"))
                    .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
            assertThat(entity.getStatus()).isEqualTo(s);
        }
        LegalEntity closed = buildEntity();
        closed.setStatus(EntityStatus.CLOSED);
        assertThatThrownBy(() -> legalEntityService.requestReinstatement(closed.getId(), UUID.randomUUID(), "why", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("legal reference");
        assertThatThrownBy(() -> legalEntityService.requestReinstatement(closed.getId(), UUID.randomUUID(), "", "ref"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(closed.getStatus()).isEqualTo(EntityStatus.CLOSED);
    }

    @Test
    @DisplayName("T6-12 state table: terminal -> PENDING_REACTIVATION only; PENDING_REACTIVATION -> ACTIVE or CLOSED; never terminal -> ACTIVE")
    void reinstatementStateTable() {
        for (EntityStatus t : new EntityStatus[]{EntityStatus.CLOSED, EntityStatus.DISSOLVED}) {
            assertThat(t.canTransitionTo(EntityStatus.PENDING_REACTIVATION)).isTrue();
            assertThat(t.canTransitionTo(EntityStatus.ACTIVE)).isFalse();
            assertThat(t.canTransitionTo(EntityStatus.SUSPENDED)).isFalse();
        }
        assertThat(EntityStatus.PENDING_REACTIVATION.canTransitionTo(EntityStatus.ACTIVE)).isTrue();
        assertThat(EntityStatus.PENDING_REACTIVATION.canTransitionTo(EntityStatus.CLOSED)).isTrue();
        assertThat(EntityStatus.PENDING_REACTIVATION.canTransitionTo(EntityStatus.SUSPENDED)).isFalse();
        assertThat(EntityStatus.PENDING_REACTIVATION.canTransitionTo(EntityStatus.DISSOLVED)).isFalse();
        assertThat(EntityStatus.ACTIVE.canTransitionTo(EntityStatus.PENDING_REACTIVATION)).isFalse();
        assertThat(EntityStatus.SUSPENDED.canTransitionTo(EntityStatus.PENDING_REACTIVATION)).isFalse();
    }

    @Test
    @DisplayName("updateEntity of an APPROVED entity audits old/new LEI, triggers re-screening and a KYC_REVIEW_REQUIRED task")
    void updateEntity_riskChange_triggersRescreenAndTask() {
        LegalEntity entity = buildEntity();
        entity.setLeiCode("OLDLEI00000000000001");
        entity.setKycStatus(de.makibytes.registerwerk.customer.api.KycStatus.APPROVED);
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(legalEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LegalEntity patch = new LegalEntity();
        patch.setLeiCode("NEWLEI00000000000001");

        legalEntityService.updateEntity(entity.getId(), patch, UUID.randomUUID());

        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(2)).publishEvent(c.capture());
        de.makibytes.registerwerk.customer.events.EntityUpdatedEvent upd = c.getAllValues().stream()
                .filter(de.makibytes.registerwerk.customer.events.EntityUpdatedEvent.class::isInstance)
                .map(de.makibytes.registerwerk.customer.events.EntityUpdatedEvent.class::cast).findFirst().orElseThrow();
        assertThat(upd.payload().toString()).contains("OLDLEI00000000000001").contains("NEWLEI00000000000001");
        assertThat(c.getAllValues()).anyMatch(
                de.makibytes.registerwerk.customer.events.EntityRiskDataChangedEvent.class::isInstance);
        verify(taskPort).open(eq(entity.getId()), eq("KYC_REVIEW_REQUIRED"), anyString(), anyString(), any());
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
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(any(Object.class));
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
        when(entityMergeRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntityMergeRecord result = legalEntityService.mergeEntities(
                source.getId(), target.getId(), EntityMergeRecord.MergeType.ABSORPTION,
                effectiveDate, "Absorbed via share purchase agreement", actorId, "SPA", null);

        verify(offboardingService).dissolveByMerger(eq(source.getId()), eq(target.getId()), eq(actorId), anyString(), eq("SPA"));
        verify(taskPort).open(eq(target.getId()), eq("KYC_REVIEW_REQUIRED"), anyString(), anyString(), any());
        assertThat(result.getSourceEntityId()).isEqualTo(source.getId());
        assertThat(result.getTargetEntityId()).isEqualTo(target.getId());
        assertThat(result.getMergeType()).isEqualTo(EntityMergeRecord.MergeType.ABSORPTION);
        assertThat(result.getRecordedBy()).isEqualTo(actorId);
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(any(Object.class));
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
                LocalDate.now(), null, UUID.randomUUID(), "r", null))
            .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class)
            .hasMessageContaining("DISSOLVED");
        assertThat(source.getStatus()).isEqualTo(EntityStatus.ACTIVE);
    }

    @Test
    @DisplayName("mergeEntities rejects merging an entity into itself")
    void mergeEntities_rejectsSelfMerge() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> legalEntityService.mergeEntities(
                id, id, EntityMergeRecord.MergeType.ABSORPTION, LocalDate.now(), null, UUID.randomUUID(), "r", null))
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

        LegalEntity result = legalEntityService.classifyClient(entity.getId(), ClientCategory.PROFESSIONAL, actorId, "MiFID opt-up evidence", null);

        assertThat(result.getClientCategory()).isEqualTo(ClientCategory.PROFESSIONAL);
        assertThat(result.getClientCategoryClassifiedAt()).isNotNull();
        assertThat(result.getClientCategoryClassifiedBy()).isEqualTo(actorId);

        ArgumentCaptor<ClientClassifiedEvent> captor = ArgumentCaptor.forClass(ClientClassifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entity.getId());
        assertThat(captor.getValue().clientCategory()).isEqualTo("PROFESSIONAL");
        assertThat(captor.getValue().payload()).containsEntry("previousCategory", "RETAIL");
        verify(dualControlGate).require("CLIENT_CLASSIFICATION_DOWNGRADE");
    }

    @Test
    @DisplayName("classifyClient throws for an unknown entity")
    void classifyClient_unknownEntity_throws() {
        UUID id = UUID.randomUUID();
        when(legalEntityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> legalEntityService.classifyClient(id, ClientCategory.RETAIL, UUID.randomUUID(), null, null))
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
        AppUser manager = new AppUser();
        manager.setEnabled(true);
        manager.setRoles(java.util.Set.of(AppUserRole.RELATIONSHIP_MANAGER));
        when(appUserRepository.findById(rmId)).thenReturn(Optional.of(manager));

        LegalEntity result = legalEntityService.assignRelationshipManager(entity.getId(), rmId, actorId);

        assertThat(result.getAssignedRelationshipManagerId()).isEqualTo(rmId);
        ArgumentCaptor<de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent> captor =
                ArgumentCaptor.forClass(de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo(entity.getId());
        assertThat(captor.getValue().relationshipManagerId()).isEqualTo(rmId);
    }

    @Test
    @DisplayName("assignRelationshipManager rejects users without the relationship-manager role")
    void assignRelationshipManager_rejectsWrongRole() {
        LegalEntity entity = buildEntity();
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser();
        user.setEnabled(true);
        user.setRoles(java.util.Set.of(AppUserRole.REGISTRY_ADMIN));
        when(legalEntityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> legalEntityService.assignRelationshipManager(
                entity.getId(), userId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RELATIONSHIP_MANAGER");
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
