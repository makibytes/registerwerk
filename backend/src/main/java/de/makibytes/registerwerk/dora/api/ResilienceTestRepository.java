package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ResilienceTestRepository extends JpaRepository<ResilienceTest, UUID> {

    List<ResilienceTest> findAllByOrderByPerformedAtDesc();

    List<ResilienceTest> findByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate threshold);
}
