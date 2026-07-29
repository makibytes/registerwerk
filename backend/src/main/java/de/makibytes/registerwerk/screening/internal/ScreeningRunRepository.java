package de.makibytes.registerwerk.screening.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScreeningRunRepository extends JpaRepository<ScreeningRun, UUID> {

    ScreeningRun findTopByEntityIdOrderByStartedAtDesc(UUID entityId);

    ScreeningRun findTopByNaturalPersonIdOrderByStartedAtDesc(UUID naturalPersonId);

    List<ScreeningRun> findByEntityIdOrderByStartedAtDesc(UUID entityId);

    /** Backs the screening-errors alerting gauge — a provider outage silently converts to
     *  ScreeningStatus.ERROR (never rethrown), so this is the only signal that new-entity
     *  approvals are currently blocked by ScreeningGateImpl. */
    long countByStatusAndStartedAtAfter(ScreeningStatus status, Instant after);

    /** All entity-IDs that ever had a screening run — used for periodic re-screen. */
    @Query("SELECT DISTINCT r.entityId FROM ScreeningRun r WHERE r.entityId IS NOT NULL")
    List<UUID> findDistinctActiveEntityIds();

    /**
     * Returns natural-person IDs whose beneficial-owner records are linked to the
     * given entity (via beneficial_owner join resolved in Java — no cross-module JPQL).
     * Delegated to a native query joining beneficial_owner.
     */
    @Query(value = """
            SELECT DISTINCT sr.natural_person_id
              FROM screening_run sr
              JOIN beneficial_owner bo ON bo.natural_person_id = sr.natural_person_id
             WHERE bo.entity_id = :entityId AND bo.ceased_at IS NULL
               AND sr.natural_person_id IS NOT NULL
            """, nativeQuery = true)
    List<UUID> findNaturalPersonIdsByEntityLinkedRuns(@Param("entityId") UUID entityId);
}
