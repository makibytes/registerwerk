package de.makibytes.registerwerk.lending.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LendingReconciliationTaskRepository extends JpaRepository<LendingReconciliationTask, UUID> {

    Optional<LendingReconciliationTask> findFirstByMarketIdAndStatusIn(
            UUID marketId, Collection<LendingReconciliationTask.Status> statuses);

    List<LendingReconciliationTask> findByStatusInOrderByDetectedAtAsc(
            Collection<LendingReconciliationTask.Status> statuses);

    long countByStatusIn(Collection<LendingReconciliationTask.Status> statuses);
}
