package de.makibytes.registerwerk.screening.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreeningHitRepository extends JpaRepository<ScreeningHit, UUID> {

    List<ScreeningHit> findByRunIdAndAcceptedIsNull(UUID runId);

    List<ScreeningHit> findByRunId(UUID runId);

    /** All unresolved hits across all entities — used for the global compliance work-queue. */
    List<ScreeningHit> findByAcceptedIsNullOrderByCreatedAtDesc();

    /** The single longest-open unresolved hit — backs the sanctions-hit-age monitoring gauge. */
    Optional<ScreeningHit> findFirstByAcceptedIsNullOrderByCreatedAtAsc();

    /**
     * Longest-open hits that still hold the gate closed: unresolved and not covered by an approved,
     * unexpired EDD record. Oldest first; callers page with a size of one.
     */
    @Query("""
            SELECT h FROM ScreeningHit h
             WHERE h.accepted IS NULL
               AND (h.eddApprovedAt IS NULL OR (h.eddReviewDue IS NOT NULL AND h.eddReviewDue <= :now))
             ORDER BY h.createdAt ASC
            """)
    List<ScreeningHit> findOldestBlockingHits(@Param("now") java.time.Instant now, Pageable page);

    /**
     * Original false-positive decisions for this subject and match fingerprint that are candidates for
     * carry-forward: accepted, with both the accepting officer and a second approver on file (SRE veto:
     * nothing less than a dual-controlled decision is ever carried), and not themselves carried copies,
     * so the validity window always counts from the original decision. Newest decision first.
     */
    @Query("""
            SELECT h FROM ScreeningHit h, ScreeningRun r
             WHERE h.runId = r.id
               AND (r.entityId = :subjectId OR r.naturalPersonId = :subjectId)
               AND h.fingerprint = :fingerprint
               AND h.accepted = true
               AND h.resolution = :resolution
               AND h.carriedFromHitId IS NULL
               AND h.acceptedBy IS NOT NULL
               AND h.dualControlApproverId IS NOT NULL
             ORDER BY h.acceptedAt DESC
            """)
    List<ScreeningHit> findCarryForwardSources(@Param("subjectId") UUID subjectId,
                                               @Param("fingerprint") String fingerprint,
                                               @Param("resolution") HitResolution resolution,
                                               Pageable page);

    /** Most recent confirmed-PEP hit for this subject and fingerprint (carried or original), newest first. */
    @Query("""
            SELECT h FROM ScreeningHit h, ScreeningRun r
             WHERE h.runId = r.id
               AND r.naturalPersonId = :subjectId
               AND h.fingerprint = :fingerprint
               AND h.resolution = :resolution
               AND h.accepted IS NULL
             ORDER BY h.createdAt DESC
            """)
    List<ScreeningHit> findConfirmedPepSources(@Param("subjectId") UUID subjectId,
                                               @Param("fingerprint") String fingerprint,
                                               @Param("resolution") HitResolution resolution,
                                               Pageable page);

    /** Unresolved confirmed-PEP hits of a natural person across all runs and providers. */
    @Query("""
            SELECT h FROM ScreeningHit h, ScreeningRun r
             WHERE h.runId = r.id
               AND r.naturalPersonId = :personId
               AND h.resolution = de.makibytes.registerwerk.screening.internal.HitResolution.CONFIRMED_PEP
               AND h.accepted IS NULL
            """)
    List<ScreeningHit> findUnresolvedConfirmedPepHits(@Param("personId") UUID personId);
}
