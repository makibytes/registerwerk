package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Breach queries key on {@code severity = MAJOR AND downgradedAt IS NULL AND <report> IS NULL} and are
 * deliberately independent of {@code status}: flipping an unreported incident to CLOSED must not take
 * it out of monitoring (6-13).
 */
public interface IctIncidentRepository extends JpaRepository<IctIncident, UUID> {

    /** Everything not closed, plus closed/other-status MAJOR incidents that still owe a report. */
    @Query("SELECT i FROM IctIncident i WHERE i.status <> 'CLOSED' " +
           "OR (i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND (i.initialReportedAt IS NULL OR i.finalReportedAt IS NULL)) " +
           "ORDER BY i.detectedAt DESC")
    List<IctIncident> findOpenOrReportsOutstanding();

    @Query("SELECT i FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.initialReportedAt IS NULL AND i.initialReportDeadline IS NOT NULL " +
           "AND i.initialReportDeadline < :now")
    List<IctIncident> findOverdueInitialReports(Instant now);

    /** Stricter 4h-from-classification sub-deadline (DORA Art. 19(4)) — distinct from, and
     *  generally due well before, the 24h-from-awareness initial-report deadline above. */
    @Query("SELECT i FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.initialReportedAt IS NULL AND i.classificationDeadline IS NOT NULL " +
           "AND i.classificationDeadline < :now")
    List<IctIncident> findOverdueClassificationReports(Instant now);

    /** 72 h after the initial notification. */
    @Query("SELECT i FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.intermediateReportedAt IS NULL AND i.intermediateReportDeadline IS NOT NULL " +
           "AND i.intermediateReportDeadline < :now")
    List<IctIncident> findOverdueIntermediateReports(Instant now);

    @Query("SELECT i FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.finalReportedAt IS NULL AND i.finalReportDeadline IS NOT NULL " +
           "AND i.finalReportDeadline < :now")
    List<IctIncident> findOverdueFinalReports(Instant now);

    // Next open (not yet breached) deadline per breach type, for registerwerk_dora_deadline_due_seconds (7B-06).
    @Query("SELECT MIN(i.classificationDeadline) FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.initialReportedAt IS NULL AND i.classificationDeadline >= :now")
    Instant nextClassificationDeadline(Instant now);

    @Query("SELECT MIN(i.initialReportDeadline) FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.initialReportedAt IS NULL AND i.initialReportDeadline >= :now")
    Instant nextInitialReportDeadline(Instant now);

    @Query("SELECT MIN(i.intermediateReportDeadline) FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.intermediateReportedAt IS NULL AND i.intermediateReportDeadline >= :now")
    Instant nextIntermediateReportDeadline(Instant now);

    @Query("SELECT MIN(i.finalReportDeadline) FROM IctIncident i WHERE i.severity = 'MAJOR' AND i.downgradedAt IS NULL " +
           "AND i.finalReportedAt IS NULL AND i.finalReportDeadline >= :now")
    Instant nextFinalReportDeadline(Instant now);

    /** Transaction-scoped advisory lock serialising concurrent automatic drafts of one source type. */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtext(:key)) AS text)", nativeQuery = true)
    String lockDraftCreation(String key);

    /** Not-yet-closed auto-opened draft for a source event type (de-duplicates automatic triggers). */
    Optional<IctIncident> findFirstBySourceEventTypeAndClassificationPendingTrueAndStatusNot(
            String sourceEventType, IctIncident.Status status);
}
