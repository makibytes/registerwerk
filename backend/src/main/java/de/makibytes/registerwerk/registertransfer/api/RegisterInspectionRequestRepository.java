package de.makibytes.registerwerk.registertransfer.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RegisterInspectionRequestRepository
        extends JpaRepository<RegisterInspectionRequest, UUID> {

    Page<RegisterInspectionRequest> findByAssetIdOrderByCreatedAtDesc(UUID assetId, Pageable pageable);

    List<RegisterInspectionRequest> findByStatus(InspectionStatus status);
}
