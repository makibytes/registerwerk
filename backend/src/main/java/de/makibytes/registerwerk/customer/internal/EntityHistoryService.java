package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.EntityMergeRecordRepository;
import de.makibytes.registerwerk.customer.api.EntityNameHistoryRepository;
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
