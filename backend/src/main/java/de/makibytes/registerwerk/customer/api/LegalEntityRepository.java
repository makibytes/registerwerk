package de.makibytes.registerwerk.customer.api;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LegalEntityRepository extends JpaRepository<LegalEntity, UUID> {

    Page<LegalEntity> findByTypeAndStatus(EntityType type, EntityStatus status, Pageable pageable);

    Page<LegalEntity> findByType(EntityType type, Pageable pageable);

    Page<LegalEntity> findByStatus(EntityStatus status, Pageable pageable);

    Optional<LegalEntity> findByEntityNumber(String entityNumber);

    Optional<LegalEntity> findByLeiCode(String leiCode);
}
