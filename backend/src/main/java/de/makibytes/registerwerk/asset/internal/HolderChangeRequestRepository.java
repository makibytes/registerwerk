package de.makibytes.registerwerk.asset.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolderChangeRequestRepository extends JpaRepository<HolderChangeRequest, UUID> {

    List<HolderChangeRequest> findByAssetIdOrderByRequestedAtDesc(UUID assetId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM HolderChangeRequest r WHERE r.id = :id AND r.assetId = :assetId")
    Optional<HolderChangeRequest> findByIdAndAssetIdForUpdate(@Param("id") UUID id, @Param("assetId") UUID assetId);
}
