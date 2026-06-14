package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IctIncidentRepository extends JpaRepository<IctIncident, UUID> {

    List<IctIncident> findByStatusNotInOrderByDetectedAtDesc(List<IctIncident.Status> statuses);

    @Query("SELECT i FROM IctIncident i WHERE i.initialReportedAt IS NULL " +
           "AND i.initialReportDeadline IS NOT NULL " +
           "AND i.initialReportDeadline < :now " +
           "AND i.status NOT IN ('CLOSED','REPORTED_TO_AUTHORITY')")
    List<IctIncident> findOverdueInitialReports(Instant now);

    @Query("SELECT i FROM IctIncident i WHERE i.finalReportedAt IS NULL " +
           "AND i.finalReportDeadline IS NOT NULL " +
           "AND i.finalReportDeadline < :now " +
           "AND i.status NOT IN ('CLOSED','REPORTED_TO_AUTHORITY')")
    List<IctIncident> findOverdueFinalReports(Instant now);
}
