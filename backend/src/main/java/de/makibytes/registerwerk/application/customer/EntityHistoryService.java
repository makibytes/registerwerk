package de.makibytes.registerwerk.application.customer;

import de.makibytes.registerwerk.domain.entity.EntityMergeRecord;
import de.makibytes.registerwerk.domain.entity.EntityNameHistory;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.EntityMergeRecordRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.EntityNameHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Provides read-only access to entity name change and merge history.
 */
@Service
@Transactional(readOnly = true)
public class EntityHistoryService {

    private final EntityNameHistoryRepository entityNameHistoryRepository;
    private final EntityMergeRecordRepository entityMergeRecordRepository;

    public EntityHistoryService(
            EntityNameHistoryRepository entityNameHistoryRepository,
            EntityMergeRecordRepository entityMergeRecordRepository) {
        this.entityNameHistoryRepository = entityNameHistoryRepository;
        this.entityMergeRecordRepository = entityMergeRecordRepository;
    }

    /**
     * Returns name change history for an entity, ordered most-recent-first.
     */
    public List<EntityNameHistory> listNameHistory(UUID entityId) {
        return entityNameHistoryRepository.findByLegalEntityIdOrderByEffectiveDateDesc(entityId);
    }

    /**
     * Returns merge records where the entity was either the source or the target.
     */
    public List<EntityMergeRecord> listMergeRecords(UUID entityId) {
        return entityMergeRecordRepository.findBySourceEntityIdOrTargetEntityId(entityId, entityId);
    }
}
