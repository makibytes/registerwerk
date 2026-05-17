package de.makibytes.registerwerk.customer.api;

import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntityNameHistoryRepository extends JpaRepository<EntityNameHistory, UUID> {

    List<EntityNameHistory> findByLegalEntityIdOrderByEffectiveDateDesc(UUID legalEntityId);
}
