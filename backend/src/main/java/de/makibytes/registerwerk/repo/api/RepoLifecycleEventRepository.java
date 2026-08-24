package de.makibytes.registerwerk.repo.api;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RepoLifecycleEventRepository extends JpaRepository<RepoLifecycleEvent, UUID> {
    List<RepoLifecycleEvent> findByRepoTradeIdOrderByCreatedAtAsc(UUID repoTradeId);
}

