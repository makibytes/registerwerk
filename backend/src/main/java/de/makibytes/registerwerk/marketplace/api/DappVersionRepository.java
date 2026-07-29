package de.makibytes.registerwerk.marketplace.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DappVersionRepository extends JpaRepository<DappVersion, UUID> {

    List<DappVersion> findByListingIdOrderByCreatedAtDesc(UUID listingId);

    List<DappVersion> findByStatusInOrderBySubmittedAtAsc(List<DappVersionStatus> statuses);

    List<DappVersion> findByStatus(DappVersionStatus status);
}
