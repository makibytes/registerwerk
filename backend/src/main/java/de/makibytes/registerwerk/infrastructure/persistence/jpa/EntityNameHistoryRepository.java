package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.EntityNameHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntityNameHistoryRepository extends JpaRepository<EntityNameHistory, UUID> {

    List<EntityNameHistory> findByLegalEntityIdOrderByEffectiveDateDesc(UUID legalEntityId);
}
