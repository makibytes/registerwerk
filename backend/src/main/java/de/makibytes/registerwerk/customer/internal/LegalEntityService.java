package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.events.EntityCreatedEvent;
import de.makibytes.registerwerk.customer.events.EntityUpdatedEvent;
import de.makibytes.registerwerk.customer.events.EntitySuspendedEvent;
import de.makibytes.registerwerk.customer.events.EntityDissolvedEvent;
import de.makibytes.registerwerk.customer.events.EntityReactivatedEvent;
import de.makibytes.registerwerk.customer.events.EntityRenamedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.EntityNameHistoryRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
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
    private final ApplicationEventPublisher eventPublisher;
    private final EntityNumberGenerator entityNumberGenerator;

    public LegalEntityService(
            LegalEntityRepository legalEntityRepository,
            EntityNameHistoryRepository entityNameHistoryRepository,
            ApplicationEventPublisher eventPublisher,
            EntityNumberGenerator entityNumberGenerator) {
        this.legalEntityRepository = legalEntityRepository;
        this.entityNameHistoryRepository = entityNameHistoryRepository;
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
}
