package de.makibytes.registerwerk.customer.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuitabilityAssessmentRepository extends JpaRepository<SuitabilityAssessment, UUID> {

    List<SuitabilityAssessment> findByEntityIdOrderByAssessedAtDesc(UUID entityId);

    Optional<SuitabilityAssessment> findFirstByEntityIdOrderByAssessedAtDesc(UUID entityId);
}
