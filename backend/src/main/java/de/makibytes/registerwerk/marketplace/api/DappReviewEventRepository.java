package de.makibytes.registerwerk.marketplace.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DappReviewEventRepository extends JpaRepository<DappReviewEvent, UUID> {

    List<DappReviewEvent> findByVersionIdOrderByCreatedAtAsc(UUID versionId);
}
