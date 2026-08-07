package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.events.EntityCreatedEvent;
import de.makibytes.registerwerk.customer.events.EntityUpdatedEvent;
import de.makibytes.registerwerk.customer.events.EntitySuspendedEvent;
import de.makibytes.registerwerk.customer.events.EntityDissolvedEvent;
import de.makibytes.registerwerk.customer.events.EntityReactivatedEvent;
import de.makibytes.registerwerk.customer.events.EntityRenamedEvent;
import de.makibytes.registerwerk.customer.events.EntityMergedEvent;
import de.makibytes.registerwerk.customer.events.ClientClassifiedEvent;
import de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent;
import de.makibytes.registerwerk.customer.events.SuitabilityAssessmentRecordedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityMergeRecordRepository;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.EntityNameHistoryRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.RiskTolerance;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service responsible for managing legal entity lifecycle.
 */
@Service
@Transactional
public class LegalEntityService {

    private static final Logger log = LoggerFactory.getLogger(LegalEntityService.class);

    private final LegalEntityRepository legalEntityRepository;
    private final EntityNameHistoryRepository entityNameHistoryRepository;
    private final EntityMergeRecordRepository entityMergeRecordRepository;
    private final SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityNumberGenerator entityNumberGenerator;

    public LegalEntityService(
            LegalEntityRepository legalEntityRepository,
            EntityNameHistoryRepository entityNameHistoryRepository,
            EntityMergeRecordRepository entityMergeRecordRepository,
            SuitabilityAssessmentRepository suitabilityAssessmentRepository,
            ApplicationEventPublisher eventPublisher,
            EntityNumberGenerator entityNumberGenerator) {
        this.legalEntityRepository = legalEntityRepository;
        this.entityNameHistoryRepository = entityNameHistoryRepository;
        this.entityMergeRecordRepository = entityMergeRecordRepository;
        this.suitabilityAssessmentRepository = suitabilityAssessmentRepository;
        this.eventPublisher = eventPublisher;
        this.entityNumberGenerator = entityNumberGenerator;
    }

    /**
     * Creates a new legal entity, assigns an entity number, and publishes an audit event.
     */
    public LegalEntity createEntity(LegalEntity entity, UUID createdBy) {
        entity.setEntityNumber(entityNumberGenerator.generateEntityNumber());
        entity.setCreatedBy(createdBy);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new EntityCreatedEvent(saved.getId(), createdBy, null, java.util.Map.of("entityNumber", saved.getEntityNumber(), "name", saved.getCurrentName())));
        log.info("Created legal entity: id={}, number={}", saved.getId(), saved.getEntityNumber());
        return saved;
    }

    /**
     * Retrieves a legal entity by ID.
     *
     * @throws EntityNotFoundException if not found
     */
    @Transactional(readOnly = true)
    public LegalEntity getEntity(UUID id) {
        return legalEntityRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", id));
    }

    /**
     * Returns a filtered, paginated list of entities.
     * If both type and status are provided, filters on both; otherwise filters on whichever is non-null.
     */
    @Transactional(readOnly = true)
    public Page<LegalEntity> listEntities(EntityType type, EntityStatus status, Pageable pageable) {
        if (type != null && status != null) {
            return legalEntityRepository.findByTypeAndStatus(type, status, pageable);
        } else if (type != null) {
            return legalEntityRepository.findByType(type, pageable);
        } else if (status != null) {
            return legalEntityRepository.findByStatus(status, pageable);
        }
        return legalEntityRepository.findAll(pageable);
    }

    /**
     * Applies non-null fields from {@code patch} to the stored entity and emits an audit event.
     */
    public LegalEntity updateEntity(UUID id, LegalEntity patch, UUID actorId) {
        LegalEntity existing = getEntity(id);
        if (patch.getCurrentName() != null) existing.setCurrentName(patch.getCurrentName());
        if (patch.getLeiCode() != null) existing.setLeiCode(patch.getLeiCode());
        if (patch.getRegistrationNumber() != null) existing.setRegistrationNumber(patch.getRegistrationNumber());
        if (patch.getRegistrationCountry() != null) existing.setRegistrationCountry(patch.getRegistrationCountry());
        if (patch.getIncorporationDate() != null) existing.setIncorporationDate(patch.getIncorporationDate());
        LegalEntity saved = legalEntityRepository.save(existing);
        eventPublisher.publishEvent(new EntityUpdatedEvent(id, actorId, null, null));
        log.info("Updated entity: id={}", id);
        return saved;
    }

    /**
     * Suspends an active entity.
     */
    public void suspendEntity(UUID id, UUID actorId) {
        LegalEntity entity = getEntity(id);
        entity.setStatus(EntityStatus.SUSPENDED);
        legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new EntitySuspendedEvent(id, actorId, null, null));
        log.info("Suspended entity: id={}", id);
    }

    /**
     * Marks an entity as dissolved.
     */
    public void dissolveEntity(UUID id, UUID actorId) {
        LegalEntity entity = getEntity(id);
        entity.setStatus(EntityStatus.DISSOLVED);
        legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new EntityDissolvedEvent(id, actorId, null, null));
        log.info("Dissolved entity: id={}", id);
    }

    /**
     * Reactivates a suspended entity.
     */
    public void reactivateEntity(UUID id, UUID actorId) {
        LegalEntity entity = getEntity(id);
        entity.setStatus(EntityStatus.ACTIVE);
        legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new EntityReactivatedEvent(id, actorId, null, null));
        log.info("Reactivated entity: id={}", id);
    }

    /**
     * Records a name change in the entity's name history and updates the current name.
     */
    public void renameEntity(UUID id, String newName, LocalDate effectiveDate, UUID actorId) {
        LegalEntity entity = getEntity(id);
        String previousName = entity.getCurrentName();

        EntityNameHistory history = new EntityNameHistory();
        history.setLegalEntityId(id);
        history.setPreviousName(previousName);
        history.setNewName(newName);
        history.setChangeType(EntityNameHistory.ChangeType.RENAME);
        history.setEffectiveDate(effectiveDate);
        history.setRecordedBy(actorId);
        entityNameHistoryRepository.save(history);

        entity.setCurrentName(newName);
        legalEntityRepository.save(entity);

        eventPublisher.publishEvent(new EntityRenamedEvent(id, actorId, null, java.util.Map.of("previousName", previousName, "newName", newName, "effectiveDate", effectiveDate.toString())));
        log.info("Renamed entity: id={}, from='{}' to='{}'", id, previousName, newName);
    }

    /**
     * Records an entity merge (M&A event): the source entity is absorbed into (or consolidated
     * with) the target entity and marked {@link EntityStatus#DISSOLVED} — it ceases independent
     * legal existence but its record, name history, and audit trail are retained (German
     * commercial law retention requirements), consistent with how {@link #dissolveEntity} treats
     * other entities that stop being an active legal person.
     */
    public EntityMergeRecord mergeEntities(UUID sourceEntityId, UUID targetEntityId,
                                            EntityMergeRecord.MergeType mergeType,
                                            LocalDate effectiveDate, String notes, UUID actorId) {
        if (sourceEntityId.equals(targetEntityId)) {
            throw new IllegalArgumentException("An entity cannot be merged into itself.");
        }
        LegalEntity source = getEntity(sourceEntityId);
        LegalEntity target = getEntity(targetEntityId);
        if (source.getStatus() == EntityStatus.DISSOLVED) {
            throw new InvalidStateTransitionException(
                    "Source entity " + sourceEntityId + " is already dissolved and cannot be merged again.");
        }
        if (target.getStatus() == EntityStatus.DISSOLVED) {
            throw new InvalidStateTransitionException(
                    "Target entity " + targetEntityId + " is dissolved and cannot absorb another entity.");
        }

        EntityMergeRecord record = new EntityMergeRecord();
        record.setSourceEntityId(sourceEntityId);
        record.setTargetEntityId(targetEntityId);
        record.setMergeType(mergeType);
        record.setEffectiveDate(effectiveDate);
        record.setNotes(notes);
        record.setRecordedBy(actorId);
        EntityMergeRecord saved = entityMergeRecordRepository.save(record);

        source.setStatus(EntityStatus.DISSOLVED);
        legalEntityRepository.save(source);

        eventPublisher.publishEvent(new EntityMergedEvent(sourceEntityId, actorId, null, Map.of(
                "targetEntityId", targetEntityId.toString(),
                "mergeType", mergeType.name(),
                "effectiveDate", effectiveDate.toString())));
        log.info("Merged entity: source={} into target={} type={}", sourceEntityId, targetEntityId, mergeType);
        return saved;
    }

    /**
     * MiFID II client classification (Annex II) — set by the firm (REGISTRY_ADMIN /
     * COMPLIANCE_OFFICER at the controller boundary), never self-declared by the client.
     */
    public LegalEntity classifyClient(UUID id, ClientCategory category, UUID actorId) {
        LegalEntity entity = getEntity(id);
        entity.setClientCategory(category);
        entity.setClientCategoryClassifiedAt(Instant.now());
        entity.setClientCategoryClassifiedBy(actorId);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new ClientClassifiedEvent(id, actorId, null, category.name()));
        log.info("Classified entity: id={} category={}", id, category);
        return saved;
    }

    /**
     * Records a new suitability assessment. Immutable/append-only (see
     * {@link SuitabilityAssessment}'s Javadoc) — a reassessment is a new row, never an edit of
     * the previous one.
     */
    public SuitabilityAssessment recordSuitabilityAssessment(
            UUID entityId, KnowledgeExperienceLevel knowledgeExperience, RiskTolerance riskTolerance,
            Integer investmentHorizonYears, boolean financialSituationAdequate, String notes, UUID actorId) {
        getEntity(entityId); // 404s if the entity doesn't exist
        SuitabilityAssessment assessment = new SuitabilityAssessment();
        assessment.setEntityId(entityId);
        assessment.setKnowledgeExperience(knowledgeExperience);
        assessment.setRiskTolerance(riskTolerance);
        assessment.setInvestmentHorizonYears(investmentHorizonYears);
        assessment.setFinancialSituationAdequate(financialSituationAdequate);
        assessment.setNotes(notes);
        assessment.setAssessedBy(actorId);
        SuitabilityAssessment saved = suitabilityAssessmentRepository.save(assessment);
        eventPublisher.publishEvent(new SuitabilityAssessmentRecordedEvent(
                entityId, actorId, null, saved.getId(), knowledgeExperience.name(), riskTolerance.name()));
        log.info("Recorded suitability assessment: entityId={} id={}", entityId, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SuitabilityAssessment> listSuitabilityAssessments(UUID entityId) {
        return suitabilityAssessmentRepository.findByEntityIdOrderByAssessedAtDesc(entityId);
    }

    @Transactional(readOnly = true)
    public Optional<SuitabilityAssessment> getLatestSuitabilityAssessment(UUID entityId) {
        return suitabilityAssessmentRepository.findFirstByEntityIdOrderByAssessedAtDesc(entityId);
    }

    /**
     * Assigns (or reassigns, or clears with {@code relationshipManagerId=null}) the client-servicing
     * relationship manager for this entity (F-BLOCKER-15). Does not validate that
     * {@code relationshipManagerId} actually holds the RELATIONSHIP_MANAGER role — {@code auth} is
     * a separate module and this follows the same raw-UUID, unvalidated-assignee convention as
     * {@code SupportTicket.assignedTo}.
     */
    public LegalEntity assignRelationshipManager(UUID entityId, UUID relationshipManagerId, UUID actorId) {
        LegalEntity entity = getEntity(entityId);
        entity.setAssignedRelationshipManagerId(relationshipManagerId);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new RelationshipManagerAssignedEvent(entityId, actorId, null, relationshipManagerId));
        log.info("Assigned relationship manager: entityId={} rmId={}", entityId, relationshipManagerId);
        return saved;
    }

    /** Entities currently assigned to a given relationship manager — the "my clients" list. */
    @Transactional(readOnly = true)
    public List<LegalEntity> listAssignedToRelationshipManager(UUID relationshipManagerId) {
        return legalEntityRepository.findByAssignedRelationshipManagerId(relationshipManagerId);
    }
}
