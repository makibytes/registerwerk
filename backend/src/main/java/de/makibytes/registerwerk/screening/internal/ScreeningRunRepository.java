package de.makibytes.registerwerk.screening.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ScreeningRunRepository extends JpaRepository<ScreeningRun, UUID> {

    ScreeningRun findTopByEntityIdOrderByStartedAtDesc(UUID entityId);

    @Query("SELECT DISTINCT r.entityId FROM ScreeningRun r WHERE r.entityId IS NOT NULL AND r.status = 'CLEAR'")
    List<UUID> findDistinctActiveEntityIds();
}
