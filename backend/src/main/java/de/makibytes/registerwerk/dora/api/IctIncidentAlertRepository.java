package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IctIncidentAlertRepository extends JpaRepository<IctIncidentAlert, UUID> {

    Optional<IctIncidentAlert> findByIncidentIdAndBreachType(UUID incidentId, String breachType);
}
