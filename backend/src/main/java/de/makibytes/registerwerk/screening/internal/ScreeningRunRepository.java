package de.makibytes.registerwerk.screening.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScreeningRunRepository extends JpaRepository<ScreeningRun, UUID> {

    ScreeningRun findTopByEntityIdOrderByStartedAtDesc(UUID entityId);

    java.util.Optional<ScreeningRun> findFirstByThresholdUsedIsNotNullOrderByStartedAtDesc();

    ScreeningRun findTopByEntityIdAndProviderOrderByStartedAtDesc(UUID entityId, String provider);

    ScreeningRun findTopByNaturalPersonIdOrderByStartedAtDesc(UUID naturalPersonId);

    ScreeningRun findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(UUID naturalPersonId, String provider);

    List<ScreeningRun> findByEntityIdOrderByStartedAtDesc(UUID entityId);

    /** Recent runs of one subject at one provider, newest first (outage-streak and last-good-result lookup). */
    List<ScreeningRun> findByEntityIdAndProviderOrderByStartedAtDesc(UUID entityId, String provider, Pageable page);

    List<ScreeningRun> findByNaturalPersonIdAndProviderOrderByStartedAtDesc(UUID naturalPersonId, String provider,
                                                                            Pageable page);

    long countByEntityIdAndStatusAndStartedAtAfter(UUID entityId, ScreeningStatus status, Instant after);

    long countByNaturalPersonIdAndStatusAndStartedAtAfter(UUID naturalPersonId, ScreeningStatus status, Instant after);

    /**
     * Subjects (entity or natural person) whose latest run at some provider ended in ERROR. Rows are
     * {@code [entity_id, natural_person_id]} (one of the two is null). Backs the retry job and the
     * degraded-state gauges; deliberately a snapshot of the <em>latest</em> run, because a later
     * successful run supersedes the outage.
     */
    @Query(value = """
            SELECT l.entity_id, l.natural_person_id FROM (
                SELECT DISTINCT ON (COALESCE(entity_id, natural_person_id), provider)
                       entity_id, natural_person_id, status
                  FROM screening_run
                 ORDER BY COALESCE(entity_id, natural_person_id), provider, started_at DESC) l
             WHERE l.status = 'ERROR'
            """, nativeQuery = true)
    List<Object[]> findSubjectsWithLatestError();

    /** How many (subject, provider) pairs currently have ERROR as their latest run. */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT DISTINCT ON (COALESCE(entity_id, natural_person_id), provider) status
                  FROM screening_run
                 ORDER BY COALESCE(entity_id, natural_person_id), provider, started_at DESC) l
             WHERE l.status = 'ERROR'
            """, nativeQuery = true)
    long countLatestError();

    /**
     * Of those, the ones that still have a completed result younger than {@code goodSince} — i.e. the
     * pairs the gate may be relying on a stale result for (the finer grace-window test is the gate's).
     */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT DISTINCT ON (COALESCE(entity_id, natural_person_id), provider)
                       COALESCE(entity_id, natural_person_id) AS subject, provider, status
                  FROM screening_run
                 ORDER BY COALESCE(entity_id, natural_person_id), provider, started_at DESC) l
             WHERE l.status = 'ERROR'
               AND EXISTS (SELECT 1 FROM screening_run g
                            WHERE COALESCE(g.entity_id, g.natural_person_id) = l.subject
                              AND g.provider = l.provider
                              AND g.status IN ('CLEAR', 'ACCEPTED', 'HIT')
                              AND g.started_at > :goodSince)
            """, nativeQuery = true)
    long countLatestErrorWithRecentGoodResult(@Param("goodSince") Instant goodSince);

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

    /**
     * Natural persons whose beneficial-owner record for the entity was ceased and who have no active
     * record for it. They stay in the screening gate while their latest run is unresolved (6-16): ceasing
     * a record must not make a sanctions hit disappear.
     */
    @Query(value = """
            SELECT DISTINCT bo.natural_person_id FROM beneficial_owner bo
             WHERE bo.entity_id = :entityId AND bo.ceased_at IS NOT NULL
               AND bo.natural_person_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM beneficial_owner a
                                WHERE a.entity_id = bo.entity_id
                                  AND a.natural_person_id = bo.natural_person_id
                                  AND a.ceased_at IS NULL)
            """, nativeQuery = true)
    List<UUID> findCeasedBeneficialOwnerPersonIds(@Param("entityId") UUID entityId);

    /**
     * Current beneficial-owner persons of the entity with their erasure flag, rows {@code [person_id,
     * redacted]}. A redacted person has no data left to screen, so "never screened" must not block it.
     */
    @Query(value = """
            SELECT DISTINCT bo.natural_person_id, np.redacted FROM beneficial_owner bo
              JOIN natural_person np ON np.id = bo.natural_person_id
             WHERE bo.entity_id = :entityId AND bo.ceased_at IS NULL
            """, nativeQuery = true)
    List<Object[]> findCurrentBeneficialOwnerPersons(@Param("entityId") UUID entityId);

    /** Natural persons currently recorded as beneficial owners of the entity (with or without a run). */
    @Query(value = """
            SELECT DISTINCT bo.natural_person_id FROM beneficial_owner bo
             WHERE bo.entity_id = :entityId AND bo.ceased_at IS NULL
               AND bo.natural_person_id IS NOT NULL
            """, nativeQuery = true)
    List<UUID> findCurrentBeneficialOwnerPersonIds(@Param("entityId") UUID entityId);
}
