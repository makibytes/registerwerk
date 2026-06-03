package de.makibytes.registerwerk.screening.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScreeningHitRepository extends JpaRepository<ScreeningHit, UUID> {

    List<ScreeningHit> findByRunIdAndAcceptedIsNull(UUID runId);

    /** All unresolved hits across all entities — used for the global compliance work-queue. */
    List<ScreeningHit> findByAcceptedIsNullOrderByCreatedAtDesc();
}
