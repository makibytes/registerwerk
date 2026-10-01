package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IctIncidentReportRepository extends JpaRepository<IctIncidentReport, UUID> {

    List<IctIncidentReport> findByIncidentIdOrderBySubmittedAtAscRecordedAtAsc(UUID incidentId);

    boolean existsByIncidentIdAndReportType(UUID incidentId, IctIncidentReport.Type reportType);
}
