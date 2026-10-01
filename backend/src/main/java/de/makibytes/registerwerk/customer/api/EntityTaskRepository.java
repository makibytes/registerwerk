package de.makibytes.registerwerk.customer.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntityTaskRepository extends JpaRepository<EntityTask, UUID> {

    Optional<EntityTask> findByEntityIdAndKindAndRefIdAndStatus(
            UUID entityId, String kind, String refId, EntityTask.Status status);

    List<EntityTask> findByEntityIdOrderByCreatedAtDesc(UUID entityId);

    List<EntityTask> findByStatusOrderByCreatedAtAsc(EntityTask.Status status);

    long countByStatus(EntityTask.Status status);

    long countByStatusAndKindNotIn(EntityTask.Status status, java.util.Collection<String> kinds);
}
