package de.makibytes.registerwerk.customer.api;

import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntityMergeRecordRepository extends JpaRepository<EntityMergeRecord, UUID> {

    List<EntityMergeRecord> findBySourceEntityIdOrTargetEntityId(UUID sourceId, UUID targetId);
}
