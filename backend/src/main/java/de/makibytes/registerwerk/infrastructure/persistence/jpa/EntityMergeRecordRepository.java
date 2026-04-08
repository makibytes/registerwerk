package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.EntityMergeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntityMergeRecordRepository extends JpaRepository<EntityMergeRecord, UUID> {

    List<EntityMergeRecord> findBySourceEntityIdOrTargetEntityId(UUID sourceId, UUID targetId);
}
