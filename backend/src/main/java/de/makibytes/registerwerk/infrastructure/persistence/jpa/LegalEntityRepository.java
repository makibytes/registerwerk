package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
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
